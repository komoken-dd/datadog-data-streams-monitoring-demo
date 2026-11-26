import datetime as dt
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('dependency_audit', str(ROOT / 'scripts/check-runtime-dependencies'))
spec = importlib.util.spec_from_loader(loader.name, loader)
audit = importlib.util.module_from_spec(spec)
loader.exec_module(audit)


class DependencyAuditTest(unittest.TestCase):
    def test_requires_resolved_runtime_inventory(self):
        with self.assertRaises(ValueError):
            audit.maven_coordinates({'components': []})
        self.assertEqual(audit.maven_coordinates({'components': [
            {'purl': 'pkg:maven/org.example/test@1.2.3?type=jar'},
            {'purl': 'pkg:maven/org.example/test@1.2.3'},
        ]}), [('org.example:test', '1.2.3')])

    def test_exceptions_expire_and_cannot_omit_accountability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'policy.json'
            entry = {'package': 'org.example:test', 'version': '1.0', 'advisory': 'GHSA-test',
                     'reason': 'Feature absent', 'owner': 'maintainer', 'expires': '2026-11-25'}
            path.write_text(json.dumps({'exceptions': [entry]}))
            self.assertEqual(len(audit.checked_exceptions(path, dt.date(2026, 11, 25))), 1)
            with self.assertRaisesRegex(ValueError, 'Expired'):
                audit.checked_exceptions(path, dt.date(2026, 11, 26))
            del entry['reason']
            path.write_text(json.dumps({'exceptions': [entry]}))
            with self.assertRaises(ValueError):
                audit.checked_exceptions(path, dt.date(2026, 11, 25))


if __name__ == '__main__':
    unittest.main()
