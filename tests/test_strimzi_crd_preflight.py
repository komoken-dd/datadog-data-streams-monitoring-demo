"""Fail-closed cluster-wide CRD upgrade sequencing without a live cluster."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StrimziCrdPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ('scripts', 'config', 'bin'):
            (self.root / directory).mkdir()
        for path in ('scripts/strimzi-crd-preflight', 'scripts/lib.sh', 'config/container-images.sh'):
            shutil.copy(ROOT / path, self.root / path)
        self.log = self.root / 'commands.jsonl'
        mock = '''#!/usr/bin/env python3
import json,os,sys
name=os.path.basename(sys.argv[0]);args=sys.argv[1:]
with open(os.environ['MOCK_LOG'],'a') as f:f.write(json.dumps([name]+args)+'\\n')
if name=='helm':
 if os.getenv('FAIL_HELM'):sys.exit(1)
 print('kind: CustomResourceDefinition\\nmetadata:\\n  name: kafkas.kafka.strimzi.io')
elif args==['config','current-context']:print(os.getenv('MOCK_CONTEXT','kind-review'))
elif '--dry-run=server' in args and os.getenv('FAIL_DRY_RUN'):sys.exit(1)
'''
        for command in ('helm', 'kubectl'):
            executable = self.root / 'bin' / command
            executable.write_text(mock)
            executable.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(self.root / 'bin') + ':' + os.environ['PATH'],
                    'MOCK_LOG': str(self.log)}

    def run_script(self, *args, **env):
        result = subprocess.run(['bash', str(self.root / 'scripts/strimzi-crd-preflight'), *args],
                                env={**self.env, **env}, capture_output=True, text=True)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, calls

    def test_plan_fetches_locked_chart_and_only_server_dry_runs(self):
        result, calls = self.run_script('plan', 'kind-review')
        self.assertEqual(result.returncode, 0, result.stderr)
        chart = next(c for c in calls if c[0] == 'helm')[-1]
        self.assertRegex(chart, r'@sha256:[a-f0-9]{64}$')
        writes = [c for c in calls if 'apply' in c]
        self.assertEqual(len(writes), 1)
        self.assertIn('--dry-run=server', writes[0])
        self.assertEqual(writes[0][1:3], ['--context', 'kind-review'])
        self.assertNotIn('--force-conflicts', writes[0])

    def test_apply_always_preflights_before_real_apply_and_waits(self):
        result, calls = self.run_script('apply', 'kind-review')
        self.assertEqual(result.returncode, 0, result.stderr)
        applies = [c for c in calls if 'apply' in c]
        self.assertEqual(len(applies), 2)
        self.assertIn('--dry-run=server', applies[0])
        self.assertNotIn('--dry-run=server', applies[1])
        self.assertIn('wait', calls[-1])
        self.assertFalse(any('--force-conflicts' in c for c in calls))

    def test_context_and_validation_failures_cannot_apply(self):
        for environment in ({'MOCK_CONTEXT': 'production'}, {'FAIL_HELM': '1'}, {'FAIL_DRY_RUN': '1'}):
            with self.subTest(environment=environment):
                self.log.unlink(missing_ok=True)
                result, calls = self.run_script('apply', 'kind-review', **environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any('apply' in c and '--dry-run=server' not in c for c in calls))

    def test_standalone_requires_explicit_context(self):
        result, calls = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
