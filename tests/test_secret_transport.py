"""Exercise actual secret-generation and curl-stdin boundaries with synthetic values."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("secret_manifest", ROOT / "scripts/secret-manifest.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SecretTransportTest(unittest.TestCase):
    def test_identities_are_independent_and_server_verifiers_match(self):
        data = module.manifest("api", "brokerage-demo", "brokerage-demo-api-auth")["stringData"]
        tokens = [data[identity + "-token"] for identity in ("operator", "simulator", "order", "notification")]
        self.assertEqual(len(set(tokens)), 4)
        for identity in ("operator", "simulator", "order", "notification"):
            self.assertEqual(hashlib.sha256(data[identity + "-token"].encode()).hexdigest(), data[identity + "-token-sha256"])
        db = module.manifest("database", "brokerage-demo", "brokerage-demo-db-order-service")["stringData"]
        self.assertNotEqual(db["runtime-password"], db["migration-password"])

    def test_runner_projection_contains_only_operator_credential(self):
        data = module.manifest("api", "brokerage-demo", "brokerage-demo-api-auth")["stringData"]
        source = {"data": {key: base64.b64encode(value.encode()).decode() for key, value in data.items()}}
        result = subprocess.run(["python3", str(ROOT / "scripts/runner-credential-secret.py"), "datadog", "brokerage-demo-runner-http"],
                                input=json.dumps(source), text=True, capture_output=True, check=True)
        credential = json.loads(json.loads(result.stdout)["stringData"]["http-token.json"])
        self.assertEqual(credential["credentials"], [{"tokenName": "Authorization", "tokenValue": "Bearer " + data["operator-token"]}])
        for identity in ("simulator", "order", "notification"):
            self.assertNotIn(data[identity + "-token"], result.stdout)

    def test_datadog_headers_are_emitted_for_stdin_and_reject_header_injection(self):
        command = ["python3", str(ROOT / "scripts/curl-auth-config.py"), "datadog"]
        api, app = "synthetic-api-key-1234567890", "synthetic-app-key-1234567890"
        result = subprocess.run(command, env=os.environ | {"DD_API_KEY": api, "DD_APP_KEY": app}, capture_output=True, text=True, check=True)
        self.assertNotIn(api, " ".join(result.args))
        self.assertNotIn(app, " ".join(result.args))
        self.assertIn(json.dumps("DD-API-KEY: " + api), result.stdout)
        self.assertNotIn(api, result.stderr)
        rejected = subprocess.run(command, env=os.environ | {"DD_API_KEY": api + "\nheader: injected", "DD_APP_KEY": app}, capture_output=True, text=True)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertEqual(rejected.stdout, "")

    def test_postgresql_secret_is_read_from_environment(self):
        with patch.dict(os.environ, {"POSTGRES_PASSWORD": "synthetic-only-password"}):
            self.assertEqual(module.manifest("postgresql", "brokerage-demo", "brokerage-demo-postgresql")["stringData"],
                             {"password": "synthetic-only-password"})
