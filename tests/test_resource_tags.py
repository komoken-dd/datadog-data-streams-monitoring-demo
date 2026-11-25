"""Regression tests using real Helm rendering and mocked external services.

Run with: python3 -m unittest discover -s tests -v
No AWS/Datadog credentials or Kubernetes cluster are used.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HELM = shutil.which("helm")
KUBECTL = shutil.which("kubectl")
BASH = "/bin/bash"  # Includes the system Bash 3.2 on macOS.
SERVICES = (
    "order-flow-simulator", "order-service", "buying-power-service",
    "exchange-gateway-simulator", "holdings-service", "trade-report-service",
    "notification-service", "push-gateway-simulator", "order-state-service",
    "exception-management-service",
)

MOCK_COMMAND = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys

name = Path(sys.argv[0]).name
args = sys.argv[1:]
root = Path(os.environ["TEST_CASE_ROOT"])
is_preflight = name == "kubectl" and args[:2] == ["apply", "--dry-run=server"]
read_only = (
    name in ("aws", "curl")
    or (name == "helm" and args[0] == "template")
    or (name == "kubectl" and args[:2] in (["config", "current-context"], ["get", "secret"]))
    or is_preflight
    or "--dry-run=client" in args
)
with (root / "commands.jsonl").open("a") as log:
    log.write(json.dumps({"name": name, "args": args, "persisted": not read_only}) + "\n")

if name == "aws" and args[:2] == ["sts", "get-caller-identity"]:
    print("123456789012")
elif name == "curl":
    print('{"status":"ok"}')
elif name == "helm" and args[0] == "template":
    sys.exit(subprocess.call([os.environ["TEST_REAL_HELM"], *args]))
elif name == "helm" and args[:3] == ["upgrade", "--install", "brokerage-demo"]:
    # Stop at the final application upgrade; never run a real Helm upgrade.
    render_args = []
    iterator = iter(args[4:])
    for arg in iterator:
        if arg == "--timeout":
            next(iterator)
        elif arg != "--wait":
            render_args.append(arg)
    result = subprocess.run([
        os.environ["TEST_REAL_HELM"], "template", args[2], args[3],
        *render_args, "--show-only", "templates/storage-class.yaml",
    ], capture_output=True, text=True, check=True)
    (root / "upgrade.yaml").write_text(result.stdout)
    sys.exit(73)
elif name == "helm":
    pass  # Operator installation and repo commands are recorded only.
elif name == "kubectl" and args[:2] == ["config", "current-context"]:
    print("test-eks-context")
elif is_preflight:
    manifest = Path(args[args.index("--filename") + 1]).read_text()
    (root / "preflight.yaml").write_text(manifest)
    existing = root / "existing.yaml"
    if os.environ.get("TEST_PREFLIGHT_ERROR"):
        print("Forbidden: StorageClass access denied", file=sys.stderr)
        sys.exit(1)
    if existing.exists() and existing.read_text().split("parameters:\n", 1)[1] != manifest.split("parameters:\n", 1)[1]:
        print("StorageClass parameters: field is immutable", file=sys.stderr)
        sys.exit(1)
elif name == "kubectl":
    if args[0] == "apply":
        sys.stdin.read()
else:
    raise SystemExit("Unexpected mock command: " + name)
'''


@unittest.skipUnless(HELM and shutil.which("jq"), "requires Helm and jq")
class ResourceTagsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="resource-tags-test-")
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        (self.work / "bin").mkdir()
        (self.work / "tmp").mkdir()
        mock = self.work / "mock-command"
        mock.write_text(MOCK_COMMAND)
        mock.chmod(0o755)
        for command in ("aws", "curl", "kubectl", "helm"):
            (self.work / "bin" / command).symlink_to(mock)
        images = self.work / "images.json"
        images.write_text(json.dumps({
            "version": "test",
            "image": {"references": {
                service: f"example.invalid/demo/{service}:test@sha256:" + "a" * 64
                for service in SERVICES
            }},
        }))
        self.env = {
            **os.environ,
            "PATH": str(self.work / "bin") + os.pathsep + os.environ["PATH"],
            "TMPDIR": str(self.work / "tmp") + "/",
            "TEST_CASE_ROOT": str(self.work),
            "TEST_REAL_HELM": HELM,
            "BROKERAGE_NAMESPACE": "brokerage-demo",
            "BROKERAGE_DEMO_AWS_ACCOUNT_ID": "123456789012",
            "BROKERAGE_DEMO_EKS_CONTEXT": "test-eks-context",
            "BROKERAGE_DEMO_IMAGE_SOURCE": "self-built",
            "BROKERAGE_DEMO_IMAGE_VALUES_FILE": str(images),
            "BROKERAGE_DEMO_CREATE_K8S_SECRETS": "true",
            "BROKERAGE_DEMO_DSM_FULL_TOPOLOGY": "false",
            "DD_API_KEY": "test-only-api-key",
            "DD_APP_KEY": "test-only-app-key",
            "DD_SITE": "datadoghq.com",
        }
        self.env.pop("BROKERAGE_DEMO_RESOURCE_TAGS", None)
        self.env.pop("TEST_PREFLIGHT_ERROR", None)

    def parse(self, tags=None):
        env = self.env.copy()
        if tags is not None:
            env["BROKERAGE_DEMO_RESOURCE_TAGS"] = tags
        return subprocess.run(
            [BASH, str(ROOT / "scripts/resource-tag-values")],
            env=env, text=True, capture_output=True,
        )

    def render(self, tags=None):
        values = self.work / "tags.json"
        result = self.parse(tags)
        self.assertEqual(result.returncode, 0, result.stderr)
        values.write_text(result.stdout)
        return subprocess.run([
            HELM, "template", "brokerage-demo", str(ROOT / "deploy/helm/brokerage-demo"),
            "--values", str(ROOT / "deploy/values/values-eks.yaml"),
            "--values", str(values), "--show-only", "templates/storage-class.yaml",
        ], capture_output=True, text=True, check=True).stdout

    def deploy(self, tags=None):
        env = self.env.copy()
        if tags is not None:
            env["BROKERAGE_DEMO_RESOURCE_TAGS"] = tags
        return subprocess.run(
            [BASH, str(ROOT / "scripts/deploy"), "eks"],
            env=env, capture_output=True, text=True, timeout=30,
        )

    def commands(self):
        log = self.work / "commands.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def assert_stopped_before_mutation(self, result):
        self.assertNotEqual(result.returncode, 0)
        self.assertNotEqual(result.returncode, 73, result.stdout + result.stderr)
        self.assertFalse([command for command in self.commands() if command["persisted"]])
        self.assertFalse((self.work / "upgrade.yaml").exists())

    def test_literal_keys_and_values(self):
        tags = " example.com/owner = Demo Team , cost:center=Research=One,enabled=true,code=001,owner=old,owner=new "
        expected = {
            "example.com/owner": "Demo Team", "cost:center": "Research=One",
            "enabled": "true", "code": "001", "owner": "new",
        }
        result = self.parse(tags)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"storageClass": {"volumeTags": expected}})
        rendered = self.render(tags)
        actual = {}
        for line in rendered.splitlines():
            if line.strip().startswith("tagSpecification_"):
                key, value = json.loads(line.split(": ", 1)[1]).split("=", 1)
                actual[key] = value
        self.assertEqual(actual, expected)

    def test_default_creator_and_team_render_as_strings(self):
        result = self.render("ts_creator=owner@example.com,ts_team=ese")
        self.assertIn('tagSpecification_1: "ts_creator=owner@example.com"', result)
        self.assertIn('tagSpecification_2: "ts_team=ese"', result)

    @unittest.skipUnless(KUBECTL, "requires kubectl for local migration-manifest validation")
    def test_migration_manifest_retains_helm_ownership(self):
        manifest = self.work / "replacement.yaml"
        manifest.write_text(self.render("example.com/owner=Demo Team"))
        result = subprocess.run([
            KUBECTL, "annotate", "--local", "--overwrite", "--filename", str(manifest),
            "meta.helm.sh/release-name=brokerage-demo",
            "meta.helm.sh/release-namespace=brokerage-demo", "--output", "json",
        ], capture_output=True, text=True, check=True)
        replacement = json.loads(result.stdout)
        self.assertEqual(replacement["metadata"]["name"], "gp3")
        self.assertEqual(replacement["metadata"]["annotations"]["meta.helm.sh/release-name"], "brokerage-demo")
        self.assertEqual(replacement["metadata"]["annotations"]["meta.helm.sh/release-namespace"], "brokerage-demo")
        self.assertEqual(replacement["metadata"]["labels"]["app.kubernetes.io/managed-by"], "Helm")
        self.assertEqual(replacement["parameters"]["tagSpecification_1"], "example.com/owner=Demo Team")

    def test_empty_and_unset_input(self):
        for tags in (None, "", " , , "):
            with self.subTest(tags=tags):
                result = self.parse(tags)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout), {"storageClass": {"volumeTags": {}}})

    def test_malformed_input_stops_before_any_external_command(self):
        for tags in ("owner", "=alice", "owner=", " =alice", "owner=   ", "good=ok,broken"):
            with self.subTest(tags=tags):
                result = self.deploy(tags)
                self.assert_stopped_before_mutation(result)
                self.assertIn("BROKERAGE_DEMO_RESOURCE_TAGS", result.stderr)
                self.assertEqual(self.commands(), [])

    def test_first_install_preflights_exact_upgrade_manifest(self):
        result = self.deploy("example.com/owner=Demo Team,ts_team=ese")
        self.assertEqual(result.returncode, 73, result.stdout + result.stderr)
        self.assertEqual((self.work / "preflight.yaml").read_text(), (self.work / "upgrade.yaml").read_text())
        commands = self.commands()
        preflight = next(i for i, cmd in enumerate(commands) if "--dry-run=server" in cmd["args"])
        self.assertTrue(any(cmd["persisted"] for cmd in commands[preflight + 1:]))
        self.assertFalse(any(cmd["persisted"] for cmd in commands[:preflight + 1]))

    def test_unchanged_storage_class_passes(self):
        tags = "ts_team=ese,example.com/owner=Demo Team"
        (self.work / "existing.yaml").write_text(self.render(tags))
        result = self.deploy(tags)
        self.assertEqual(result.returncode, 73, result.stdout + result.stderr)

    def test_adding_tags_to_existing_storage_class_stops_safely(self):
        (self.work / "existing.yaml").write_text(self.render())
        result = self.deploy("ts_team=ese")
        self.assert_stopped_before_mutation(result)
        self.assertIn("immutable", result.stderr)
        self.assertIn("docs/demo-runbook.md#changing-ebs-volume-tags", result.stderr)

    def test_removing_tags_from_existing_storage_class_stops_safely(self):
        (self.work / "existing.yaml").write_text(self.render("ts_team=ese"))
        self.assert_stopped_before_mutation(self.deploy())

    def test_preflight_access_error_stops_safely(self):
        self.env["TEST_PREFLIGHT_ERROR"] = "forbidden"
        result = self.deploy("ts_team=ese")
        self.assert_stopped_before_mutation(result)
        self.assertIn("Forbidden", result.stderr)

    def test_untagged_deployment_still_passes(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 73, result.stdout + result.stderr)
        self.assertNotIn("tagSpecification_", (self.work / "upgrade.yaml").read_text())


if __name__ == "__main__":
    unittest.main()
