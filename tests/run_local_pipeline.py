#!/usr/bin/env python3
"""Opt-in local pipeline smoke test; see docs/local-pipeline-test.md."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ['push-gateway-simulator', 'buying-power-service', 'exchange-gateway-simulator',
            'holdings-service', 'trade-report-service', 'notification-service', 'order-state-service',
            'exception-management-service', 'order-service', 'order-flow-simulator']
PORTS = {service: 18100 + i for i, service in enumerate(SERVICES)}
SCHEMAS = {'order-service': 'order_service', 'buying-power-service': 'buying_power_service',
           'exchange-gateway-simulator': 'exchange_gateway', 'holdings-service': 'holdings_service',
           'trade-report-service': 'trade_report_service', 'order-state-service': 'order_state_service',
           'exception-management-service': 'exception_management_service'}
LISTENERS = set(SERVICES[1:8])
INCOMING = {'order-service': 'simulator', 'buying-power-service': 'order',
            'exchange-gateway-simulator': 'simulator', 'push-gateway-simulator': 'notification'}
OUTGOING = {'order-flow-simulator': 'simulator', 'order-service': 'order',
            'notification-service': 'notification'}
DB_KEYS = {'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'SPRING_FLYWAY_ENABLED'}
TOKEN_KEYS = {'operator', 'simulator', 'order', 'notification'}


class HarnessError(ValueError):
    """An intentionally public error containing no configuration values."""


def require(condition, message):
    if not condition:
        raise HarnessError(message)


def loopback_host(host):
    if host == 'localhost':
        return all(ipaddress.ip_address(row[4][0]).is_loopback
                   for row in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM))
    try:
        return ipaddress.ip_address(host or '').is_loopback
    except ValueError:
        return False


def validate_settings(settings):
    """Validate every destination before opening logs, starting Java, or sending requests."""
    require(isinstance(settings, dict), 'Configuration must be a JSON object')
    database = settings.get('database', '')
    require(isinstance(database, str) and re.fullmatch(r'[a-z][a-z0-9_]*_test', database),
            'Only an explicitly named *_test database is allowed')
    if 'root' in settings:
        require(Path(settings['root']).resolve() == ROOT, 'Configured repository root does not match this checkout')
    services = settings.get('services', {})
    require(isinstance(services, dict) and set(services) == set(SCHEMAS),
            'Exactly the seven schema-scoped database service configurations are required')
    endpoints = set()
    for service, schema in SCHEMAS.items():
        env = services[service]
        require(isinstance(env, dict) and set(env) == DB_KEYS,
                'Database service configuration contains missing or unsupported environment keys')
        require(all(isinstance(value, str) for value in env.values()), 'Database environment values must be strings')
        url = env['DATABASE_URL']
        require(url.startswith('jdbc:postgresql://'), 'A local PostgreSQL JDBC URL is required')
        parsed = urlsplit(url[len('jdbc:'):])
        require(loopback_host(parsed.hostname) and parsed.username is None and parsed.password is None,
                'PostgreSQL must use a loopback host without URL credentials')
        require(parsed.port is not None and 1024 < parsed.port <= 65535 and parsed.port != 5432,
                'PostgreSQL must use an explicit non-default local test port')
        require(parsed.path == '/' + database and not parsed.query and not parsed.fragment,
                'Every JDBC URL must reference the selected *_test database without URL options')
        require(env['DATABASE_USERNAME'] == 'brokerage_' + schema,
                'Each service must use its dedicated schema-scoped runtime role')
        require(bool(env['DATABASE_PASSWORD']) and env['SPRING_FLYWAY_ENABLED'] == 'false',
                'Runtime credentials and disabled runtime migrations are required')
        endpoints.add((parsed.hostname, parsed.port))
    require(len(endpoints) == 1, 'All seven services must use the same isolated test database endpoint')
    kafka = settings.get('kafka_bootstrap', '127.0.0.1:19092')
    require(kafka in ('127.0.0.1:19092', 'localhost:19092', '[::1]:19092'),
            'Kafka must use one loopback test broker on port 19092')
    require(loopback_host(urlsplit('tcp://' + kafka).hostname), 'Kafka must resolve only to loopback')
    tokens = settings.get('tokens', {})
    require(isinstance(tokens, dict) and set(tokens) == TOKEN_KEYS,
            'Four independent ephemeral test credentials are required')
    require(all(isinstance(token, str) and re.fullmatch(r'[a-f0-9]{64}', token) for token in tokens.values())
            and len(set(tokens.values())) == 4, 'Test credentials must be independent random 32-byte hex values')
    return settings


def clean_environment(parent):
    # An allowlist excludes DD/OTel credentials, agents, JVM options, proxies,
    # Spring overrides and cloud credentials from the caller's environment.
    return {key: parent[key] for key in ('PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'LANG', 'LC_ALL', 'TZ')
            if key in parent}


def reserve_ports(ports):
    leases = {}
    try:
        for service, port in ports.items():
            lease = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                lease.bind(('127.0.0.1', port))
            except OSError:
                lease.close()
                raise HarnessError('A harness HTTP port is already in use; no Java services were started') from None
            leases[service] = lease
        return leases
    except Exception:
        for lease in leases.values():
            lease.close()
        raise


def preflight(settings):
    require(Path.cwd().resolve() == ROOT, 'Run the local pipeline test from this repository root')
    validate_settings(settings)
    java = settings.get('java') or str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java')
    if not Path(java).is_file():
        java = shutil.which('java')
    require(java and Path(java).is_absolute() and os.access(java, os.X_OK), 'An executable Java 21+ installation is required')
    lsof = shutil.which('lsof')
    require(lsof, 'lsof is required to verify HTTP socket ownership')
    for service in SERVICES:
        require((ROOT / 'apps' / service / 'target' / (service + '-0.1.0-SNAPSHOT.jar')).is_file(),
                'Package all ten application JARs before running this harness')
    leases = reserve_ports(PORTS)
    return java, lsof, leases


class PipelineHarness:
    def __init__(self, settings, java, lsof, leases):
        self.settings = settings
        self.tokens = settings['tokens']
        self.java, self.lsof, self.leases = java, lsof, leases
        self.processes, self.logs = {}, []
        self.output = Path(tempfile.mkdtemp(prefix='brokerage-local-e2e-'))
        # Ignore any parent HTTP proxy settings, even for the Python requests.
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def owns_port(self, service):
        process = self.processes[service]
        if process.poll() is not None:
            return False
        result = subprocess.run([self.lsof, '-nP', '-a', '-p', str(process.pid),
                                 '-iTCP:' + str(PORTS[service]), '-sTCP:LISTEN', '-Fp'],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                env=clean_environment(os.environ), timeout=5)
        return result.returncode == 0 and ('p' + str(process.pid)) in result.stdout.splitlines()

    def request(self, service, path, method='GET', token='operator', body=None, headers=None):
        require(self.owns_port(service), 'The target HTTP socket is not owned by this harness child')
        request_headers = {} if headers is None else dict(headers)
        if token:
            request_headers['Authorization'] = 'Bearer ' + self.tokens[token]
        if body is not None:
            request_headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(f'http://127.0.0.1:{PORTS[service]}' + path,
                                        data=None if body is None else json.dumps(body).encode(),
                                        headers=request_headers, method=method)
        try:
            with self.http.open(request, timeout=8) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            data = error.read()
            return error.code, json.loads(data) if data else None

    def expect(self, service, path, status, **kwargs):
        actual, body = self.request(service, path, **kwargs)
        require(actual == status, f'Unexpected HTTP status for {service} {path}: {actual}')
        return body

    def environment(self, service):
        env = clean_environment(os.environ)
        env.update({'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(PORTS[service]),
                    'KAFKA_BOOTSTRAP_SERVERS': self.settings.get('kafka_bootstrap', '127.0.0.1:19092'),
                    'DD_TRACE_ENABLED': 'false', 'DD_DATA_STREAMS_ENABLED': 'false',
                    'DD_METRICS_ENABLED': 'false', 'OTEL_SDK_DISABLED': 'true',
                    'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED': 'true',
                    'SPRING_FLYWAY_ENABLED': 'false', 'BASELINE_PER_SECOND': '0',
                    'EXCHANGE_FULL_FILL_PROBABILITY': '1', 'EXCHANGE_RANDOM_WALK_PROBABILITY': '0',
                    'BROKERAGE_DEMO_OPERATOR_TOKEN_SHA256': hashlib.sha256(self.tokens['operator'].encode()).hexdigest()})
        if service in INCOMING:
            env['BROKERAGE_DEMO_ALLOWED_SERVICE_TOKEN_SHA256'] = hashlib.sha256(self.tokens[INCOMING[service]].encode()).hexdigest()
        if service in OUTGOING:
            env['BROKERAGE_DEMO_CLIENT_TOKEN'] = self.tokens[OUTGOING[service]]
        if service in ['buying-power-service', 'order-state-service']:
            env['BROKERAGE_DEMO_READ_ONLY_TOKEN_SHA256'] = hashlib.sha256(self.tokens['simulator'].encode()).hexdigest()
        if service in LISTENERS:
            env.update({'DEMO_HEALTH_KAFKA_LISTENERS': 'true',
                        'MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE': 'readinessState,brokerageKafka'})
        for key, target in {'ORDER_SERVICE_URL': 'order-service', 'BUYING_POWER_SERVICE_URL': 'buying-power-service',
                            'ORDER_STATE_SERVICE_URL': 'order-state-service', 'EXCHANGE_GATEWAY_URL': 'exchange-gateway-simulator',
                            'PUSH_GATEWAY_URL': 'push-gateway-simulator'}.items():
            env[key] = 'http://127.0.0.1:' + str(PORTS[target])
        env.update(self.settings['services'].get(service, {}))
        return env

    def wait_for_order(self, order_id, seconds=100):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            status, state = self.request('order-state-service', '/internal/orders/' + order_id)
            if status == 200:
                executions = state['executions']
                complete = state['status'] == 'FILLED' and executions and all(
                    row['buyingPowerReflectedAt'] and row['tradeReportIssuedAt'] and row['notificationDeliveredAt']
                    for row in executions)
                if complete and all(self.expect('holdings-service', '/internal/holdings/events/' + row['executionEventId'], 200)['applied']
                                    for row in executions):
                    return state
            time.sleep(.5)
        raise HarnessError('Order downstream fanout did not complete within the test deadline')

    def exercise(self):
        expect, wait_for_order = self.expect, self.wait_for_order
        for service in SERVICES:
            expect(service, '/actuator/health/liveness', 200, token=None)
            expect(service, '/actuator/metrics', 401, token=None)
        print('All 10 liveness/readiness UP and metrics protected.', flush=True)
        expect('order-flow-simulator', '/internal/demo/stop', 200, method='POST', body={})
        expect('buying-power-service', '/internal/buying-power/accounts/A00001', 200, token='simulator')
        expect('buying-power-service', '/internal/demo/delay', 401, method='POST', token='simulator', body={})
        expect('push-gateway-simulator', '/internal/demo/outage', 401, method='POST', token='notification', body={})
        body = {'accountId': 'A00001', 'symbol': 'D101', 'side': 'BUY', 'orderType': 'MARKET', 'quantity': 100}
        headers = {'Idempotency-Key': str(uuid.uuid4())}
        accepted = expect('order-service', '/orders', 202, method='POST', token='simulator', body=body, headers=headers)
        replay = expect('order-service', '/orders', 202, method='POST', token='simulator', body=body, headers=headers)
        require(accepted['orderId'] == replay['orderId'], 'Idempotency replay changed the order identity')
        wait_for_order(accepted['orderId'])
        print('BUY full fanout and idempotency replay PASS.', flush=True)
        body['side'] = 'SELL'
        sold = expect('order-service', '/orders', 202, method='POST', token='simulator', body=body,
                      headers={'Idempotency-Key': str(uuid.uuid4())})
        wait_for_order(sold['orderId'])
        print('SELL full fanout PASS.', flush=True)
        expect('push-gateway-simulator', '/internal/demo/outage', 200, method='POST', body={})
        body['side'] = 'BUY'
        delayed = expect('order-service', '/orders', 202, method='POST', token='simulator', body=body,
                         headers={'Idempotency-Key': str(uuid.uuid4())})
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            unresolved = expect('exception-management-service', '/internal/exceptions', 200)
            if any(row['orderId'] == delayed['orderId'] for row in unresolved['exceptions']):
                break
            time.sleep(1)
        else:
            raise HarnessError('Failed push did not reach DLT')
        print('Provider outage -> both delayed retries -> DLT PASS.', flush=True)
        expect('exception-management-service', '/internal/demo/redrive', 401, method='POST', token='notification', body={})
        expect('push-gateway-simulator', '/internal/demo/recover', 200, method='POST', body={})
        redriven = expect('exception-management-service', '/internal/demo/redrive', 200, method='POST', body={})
        require(redriven['redrivenCount'] >= 1 and redriven['remainingCount'] == 0, 'Redrive did not queue the pending notifications')
        wait_for_order(delayed['orderId'], 30)
        repeated = expect('exception-management-service', '/internal/demo/redrive', 200, method='POST', body={})
        require(repeated['redrivenCount'] == 0 and repeated['remainingCount'] == 0, 'Repeated redrive was not a no-op')
        print('Provider recovery -> redrive -> all downstream marks; repeated redrive no-op PASS.', flush=True)
        expect('order-flow-simulator', '/internal/demo/opening-rush', 200, method='POST', body={})
        expect('order-flow-simulator', '/internal/demo/stop', 200, method='POST', body={})
        print('Simulator/exchange authenticated control JSON PASS.', flush=True)

    def run(self):
        print('Private test logs:', self.output, flush=True)
        try:
            for service in SERVICES:
                log = (self.output / (service + '.log')).open('w')
                self.logs.append(log)
                self.leases.pop(service).close()
                jar = ROOT / 'apps' / service / 'target' / (service + '-0.1.0-SNAPSHOT.jar')
                self.processes[service] = subprocess.Popen([self.java, '-Xms32m', '-Xmx192m', '-jar', str(jar)],
                                                           env=self.environment(service), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 180
            pending = set(SERVICES)
            while pending and time.monotonic() < deadline:
                for service in list(pending):
                    require(self.processes[service].poll() is None, service + ' exited during startup; inspect its private log')
                    if not self.owns_port(service):
                        continue
                    try:
                        if self.request(service, '/actuator/health/readiness', token=None)[0] == 200:
                            pending.remove(service)
                            print('Readiness UP:', service, flush=True)
                    except (OSError, TimeoutError):
                        pass
                if pending:
                    time.sleep(1)
            require(not pending, 'Readiness deadline exceeded; inspect private service logs')
            self.exercise()
            print('LOCAL END-TO-END PASS', flush=True)
        finally:
            for lease in self.leases.values():
                lease.close()
            # Popen tracks only children created above; never kill by port or process name.
            for process in reversed(list(self.processes.values())):
                if process.poll() is None:
                    process.terminate()
            for process in self.processes.values():
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            for log in self.logs:
                log.close()
            print('Stopped all harness Java children; database and broker remain unchanged.', flush=True)


def main():
    try:
        source = os.environ.get('BROKERAGE_E2E_CONFIG')
        require(source, 'Set BROKERAGE_E2E_CONFIG to a private local test configuration JSON file')
        path = Path(source)
        info = path.stat()
        require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) & 0o077 == 0,
                'The test configuration must be owned by the current user with mode 0600 or stricter')
        settings = json.loads(path.read_text())
        java, lsof, leases = preflight(settings)
        PipelineHarness(settings, java, lsof, leases).run()
        return 0
    except HarnessError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        # Deliberately omit configuration/body/exception text: they can contain keys.
        print('Local pipeline test refused or failed; check the documented prerequisites and private service logs.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
