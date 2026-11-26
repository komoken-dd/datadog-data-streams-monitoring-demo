import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_pipeline', ROOT / 'tests/run_local_pipeline.py')
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)


def test_settings():
    return {'database': 'pipeline_guard_test', 'kafka_bootstrap': '127.0.0.1:19092',
            'tokens': {key: f'{i:064x}' for i, key in enumerate(sorted(pipeline.TOKEN_KEYS), 1)},
            'services': {service: {
                'DATABASE_URL': 'jdbc:postgresql://127.0.0.1:55440/pipeline_guard_test',
                'DATABASE_USERNAME': 'brokerage_' + schema, 'DATABASE_PASSWORD': 'test-only',
                'SPRING_FLYWAY_ENABLED': 'false'} for service, schema in pipeline.SCHEMAS.items()}}


class LocalPipelineGuardsTest(unittest.TestCase):
    def assert_refused_before_start(self, settings):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'test-config.json'
            config.write_text(json.dumps(settings))
            config.chmod(0o600)
            with patch.dict(os.environ, {'BROKERAGE_E2E_CONFIG': str(config)}), \
                    patch.object(pipeline.subprocess, 'Popen') as start, \
                    patch.object(pipeline.urllib.request, 'build_opener') as http, \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(pipeline.main(), 2)
                start.assert_not_called()
                http.assert_not_called()

    def test_refuses_remote_database_and_kafka_before_any_service_start(self):
        for url in ('jdbc:postgresql://db.example.com:55440/pipeline_guard_test',
                    'jdbc:postgresql://127.0.0.1:55440/pipeline_guard_test?host=production',
                    'jdbc:postgresql://127.0.0.1,db.example.com:55440/pipeline_guard_test'):
            settings = test_settings()
            settings['services']['order-service']['DATABASE_URL'] = url
            with self.subTest(url=url):
                self.assert_refused_before_start(settings)
        settings = test_settings()
        settings['kafka_bootstrap'] = 'broker.example.com:19092'
        self.assert_refused_before_start(settings)

    def test_refuses_live_database_and_migration_credentials(self):
        settings = test_settings()
        settings['database'] = 'brokerage'
        self.assert_refused_before_start(settings)
        settings = test_settings()
        settings['services']['order-service']['DATABASE_USERNAME'] = 'postgres'
        self.assert_refused_before_start(settings)
        settings = test_settings()
        settings['services']['order-service']['SPRING_FLYWAY_ENABLED'] = 'true'
        self.assert_refused_before_start(settings)

    def test_refuses_a_different_checkout_or_working_directory(self):
        settings = test_settings()
        settings['root'] = '/tmp/another-checkout'
        self.assert_refused_before_start(settings)
        with patch.object(pipeline.Path, 'cwd', return_value=Path('/tmp')):
            self.assert_refused_before_start(test_settings())

    def test_busy_port_prevents_any_child_or_http_request(self):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            port = occupied.getsockname()[1]
            with patch.object(pipeline, 'PORTS', {'order-service': port}), \
                    patch.object(pipeline.Path, 'is_file', return_value=True), \
                    patch.object(pipeline.os, 'access', return_value=True), \
                    patch.object(pipeline.shutil, 'which', return_value='/usr/bin/java'):
                settings = test_settings()
                settings['java'] = '/usr/bin/java'
                self.assert_refused_before_start(settings)

    def test_parent_credentials_agents_proxies_and_spring_overrides_do_not_reach_children(self):
        parent = {'PATH': '/usr/bin', 'HOME': '/tmp/test-home', 'DD_API_KEY': 'must-not-forward',
                  'DD_APP_KEY': 'must-not-forward', 'AWS_SECRET_ACCESS_KEY': 'must-not-forward',
                  'JAVA_TOOL_OPTIONS': '-javaagent:unwanted', 'JDK_JAVA_OPTIONS': '-javaagent:unwanted',
                  'HTTP_PROXY': 'http://proxy.example.com', 'SPRING_APPLICATION_JSON': '{}'}
        self.assertEqual(pipeline.clean_environment(parent), {'PATH': '/usr/bin', 'HOME': '/tmp/test-home'})
        settings = test_settings()
        harness = object.__new__(pipeline.PipelineHarness)
        harness.settings = settings
        harness.tokens = settings['tokens']
        with patch.dict(os.environ, parent, clear=True):
            env = harness.environment('buying-power-service')
        self.assertNotIn('BROKERAGE_DEMO_CLIENT_TOKEN', env)
        for key in parent.keys() - {'PATH', 'HOME'}:
            self.assertNotIn(key, env)
        self.assertEqual(env['DD_TRACE_ENABLED'], 'false')
        self.assertEqual(env['DD_METRICS_ENABLED'], 'false')

    def test_refuses_response_from_a_process_it_did_not_start(self):
        harness = object.__new__(pipeline.PipelineHarness)
        harness.processes = {'order-service': unittest.mock.Mock(pid=123, poll=lambda: None)}
        harness.lsof = '/usr/bin/lsof'
        with patch.object(pipeline.subprocess, 'run', return_value=unittest.mock.Mock(returncode=0, stdout='p999\n')):
            self.assertFalse(harness.owns_port('order-service'))
        with patch.object(harness, 'owns_port', return_value=False), \
                patch.object(pipeline.urllib.request, 'Request') as request:
            with self.assertRaises(ValueError):
                harness.request('order-service', '/orders')
            request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
