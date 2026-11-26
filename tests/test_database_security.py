"""Opt-in PostgreSQL role/migration tests in a disposable, explicitly named test container.

BROKERAGE_TEST_POSTGRES_CONTAINER=brokerage-security-postgres-test python3 -m unittest discover -s tests -p test_database_security.py -v
No existing demo database is opened. Each run creates and drops its own *_test database.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = os.environ.get("BROKERAGE_TEST_POSTGRES_CONTAINER", "")
SERVICES = {
    "order-service": (1, "order_service"), "buying-power-service": (2, "buying_power_service"),
    "exchange-gateway-simulator": (3, "exchange_gateway"), "holdings-service": (4, "holdings_service"),
    "trade-report-service": (5, "trade_report_service"), "order-state-service": (8, "order_state_service"),
    "exception-management-service": (9, "exception_management_service"),
}


@unittest.skipUnless(CONTAINER and shutil.which("podman") and shutil.which("helm"), "requires an explicitly selected disposable PostgreSQL test container")
class DatabaseSecurityTest(unittest.TestCase):
    @classmethod
    def query(cls, sql, database=None, check=True):
        result = subprocess.run(["podman", "exec", "-i", CONTAINER, "psql", "-X", "-qAt", "-U", "audit_admin",
                               "-d", database or cls.database, "--set", "ON_ERROR_STOP=1"],
                              input=sql, capture_output=True, text=True)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    @classmethod
    def setUpClass(cls):
        if not re.fullmatch(r"brokerage-security-[a-z0-9-]*test", CONTAINER):
            raise ValueError("Only an explicitly named brokerage-security-...test container is allowed")
        cls.database = "security_roles_" + uuid.uuid4().hex[:12] + "_test"
        cls.query('CREATE DATABASE "' + cls.database + '";', database="postgres")
        cls.addClassCleanup(lambda: cls.query('DROP DATABASE "' + cls.database + '" WITH (FORCE);', database="postgres"))
        rendered = subprocess.check_output(["helm", "template", "brokerage-demo", str(ROOT / "deploy/helm/brokerage-demo"),
                                            "--show-only", "templates/database-bootstrap.yaml"], text=True)
        raw = rendered.split("  roles.sql: |\n", 1)[1].split("\n---", 1)[0]
        sql = "\n".join(line[4:] for line in raw.splitlines())
        env = "".join(f"\\setenv DB_{index}_{kind}_PASSWORD 'disposable-test-only-{kind.lower()}'\n"
                      for index, _ in SERVICES.values() for kind in ("RUNTIME", "MIGRATION"))
        cls.bootstrap = env + sql
        cls.query(cls.bootstrap)
        for service, (_, schema) in SERVICES.items():
            role = "brokerage_" + schema + "_migrator"
            migrations = sorted((ROOT / "apps" / service / "src/main/resources/db/migration").glob("V*.sql"),
                                key=lambda p: int(p.name.split("__", 1)[0][1:]))
            for path in migrations:
                cls.query(f'SET ROLE "{role}";\n' + path.read_text() + "\nRESET ROLE;")
            history = "flyway_schema_history_" + schema
            cls.query(f'SET ROLE "{role}"; CREATE TABLE "{schema}"."{history}" (version varchar primary key); '
                      f'INSERT INTO "{schema}"."{history}" VALUES (\'1\'); '
                      f'REVOKE ALL ON TABLE "{schema}"."{history}" FROM "brokerage_{schema}"; RESET ROLE;')
        cls.query(cls.bootstrap)

    def test_runtime_roles_have_no_superuser_or_cross_schema_privileges(self):
        for _, schema in SERVICES.values():
            role = "brokerage_" + schema
            result = self.query(f"SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname='{role}';")
            self.assertEqual(result.stdout.strip(), "f")
            other = "holdings_service" if schema != "holdings_service" else "buying_power_service"
            self.assertEqual(self.query(f"SELECT has_schema_privilege('{role}','{schema}','USAGE'), has_schema_privilege('{role}','{schema}','CREATE'), has_schema_privilege('{role}','{other}','USAGE');").stdout.strip(), "t|f|f")
        result = self.query("SET ROLE brokerage_order_service; SELECT * FROM buying_power_service.accounts;", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("permission denied", result.stderr)

    def test_bootstrap_never_reopens_migration_history(self):
        self.query(self.bootstrap)
        for _, schema in SERVICES.values():
            self.assertEqual(self.query(f"SELECT has_table_privilege('brokerage_{schema}', '{schema}.flyway_schema_history_{schema}', 'UPDATE');").stdout.strip(), "f")
        self.assertEqual(self.query("SELECT has_table_privilege('brokerage_order_service','order_service.epoch_budget','UPDATE');").stdout.strip(), "f")

    def test_legacy_public_history_is_moved_without_losing_version(self):
        self.query("ALTER TABLE order_state_service.flyway_schema_history_order_state_service SET SCHEMA public;")
        self.query(self.bootstrap)
        self.assertEqual(self.query("SELECT version FROM order_state_service.flyway_schema_history_order_state_service;").stdout.strip(), "1")
        self.assertEqual(self.query("SELECT to_regclass('public.flyway_schema_history_order_state_service') IS NULL;").stdout.strip(), "t")

    def test_conflicting_history_fails_closed(self):
        self.query("CREATE TABLE public.flyway_schema_history_order_service (version varchar);")
        try:
            result = self.query(self.bootstrap, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Conflicting legacy and scoped migration history", result.stderr)
        finally:
            self.query("DROP TABLE public.flyway_schema_history_order_service;")
