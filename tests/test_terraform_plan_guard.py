"""Exercise the real wrapper with local-only Terraform built-in resources."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("terraform"), "Terraform is required")
class SavedPlanIsolationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="brokerage-plan-test-")
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        self.tf = self.root / "infra/datadog-terraform"
        self.tf.mkdir(parents=True)
        for name in ("datadog-terraform", "terraform-plan-guard.py", "lib.sh", "curl-auth-config.py"):
            shutil.copy2(ROOT / "scripts" / name, self.root / "scripts" / name)
        (self.tf / "main.tf").write_text('''
terraform {
  backend "local" {}
}
variable "environment" { type = string }
variable "datadog_site" { type = string }
resource "terraform_data" "demo" { input = var.environment }
output "environment" { value = terraform_data.demo.output }
''')
        self.variables = self.root / "selected.tfvars"
        self.variables.write_text('environment = "local"\ndatadog_site = "datadoghq.com"\n')
        self.state = self.root / "state-a.tfstate"
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        curl = bin_dir / "curl"
        curl.write_text('#!/bin/sh\ncat >/dev/null\nprintf \'{"status":"ok"}\\n\'\n')
        curl.chmod(0o700)
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("TF_VAR_", "TF_CLI_ARGS")) and k != "TF_WORKSPACE"}
        self.env.update({
            "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
            "DD_API_KEY": "a" * 32,
            "DD_APP_KEY": "b" * 40,
            "DD_SITE": "datadoghq.com",
            "BROKERAGE_DEMO_TF_STATE": str(self.state),
            "BROKERAGE_DEMO_TFVARS": str(self.variables),
            "CHECKPOINT_DISABLE": "1",
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_wrapper(self, action, succeeds=True, **changes):
        result = subprocess.run(
            ["bash", str(self.root / "scripts/datadog-terraform"), action],
            env=self.env | changes, capture_output=True, text=True,
        )
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_switching_state_cannot_apply_or_destroy_another_environment(self):
        for plan, apply in (("plan", "apply"), ("destroy-plan", "destroy-apply")):
            self.run_wrapper(plan)
            b = self.root / "state-b.tfstate"
            result = self.run_wrapper(apply, False,
                                      BROKERAGE_DEMO_TF_STATE=str(b),
                                      CONFIRM_DATADOG_DESTROY="brokerage-demo")
            self.assertIn("Missing saved", result.stderr)
            self.assertFalse(self.state.exists())
            self.assertFalse(b.exists())

    def test_changed_selected_variables_reject_before_apply(self):
        self.run_wrapper("plan")
        self.variables.write_text('environment = "demo"\ndatadog_site = "datadoghq.com"\n')
        self.run_wrapper("apply", False)
        self.assertFalse(self.state.exists())

    def test_changed_auto_variables_configuration_or_plan_bytes_are_rejected(self):
        for path, value in ((self.tf / "unexpected.auto.tfvars", 'environment = "unexpected"'),
                            (self.tf / "extra.tf", 'locals { changed = true }')):
            self.run_wrapper("plan")
            path.write_text(value)
            self.run_wrapper("apply", False)
            self.assertFalse(self.state.exists())
            path.unlink()
        self.run_wrapper("plan")
        plan = next((self.tf / ".terraform-plans").rglob("*.tfplan"))
        plan.write_bytes(plan.read_bytes() + b"tampered")
        self.run_wrapper("apply", False)
        self.assertFalse(self.state.exists())

    def test_changed_site_or_credential_selection_is_rejected(self):
        self.run_wrapper("plan")
        self.run_wrapper("apply", False, DD_SITE="ap1.datadoghq.com")
        self.run_wrapper("apply", False, DD_APP_KEY="another-organization-key")
        self.assertFalse(self.state.exists())

    def test_successful_apply_consumes_plan_and_uses_expected_state(self):
        self.run_wrapper("plan")
        self.run_wrapper("apply")
        state = json.loads(self.state.read_text())
        self.assertEqual(state["outputs"]["environment"]["value"], "local")
        self.assertEqual(list((self.tf / ".terraform-plans").rglob("*.tfplan*")), [])
        self.run_wrapper("apply", False)

    def test_scope_lock_and_cli_override_fail_closed(self):
        scope = hashlib.sha256(str(self.state.resolve()).encode()).hexdigest()[:24]
        lock = self.tf / ".terraform-profiles" / (scope + ".lock")
        lock.mkdir(parents=True)
        self.run_wrapper("plan", False)
        lock.rmdir()
        self.run_wrapper("plan", False, TF_CLI_ARGS_plan="-var=environment=unexpected")
        self.assertFalse(self.state.exists())


if __name__ == "__main__":
    unittest.main()
