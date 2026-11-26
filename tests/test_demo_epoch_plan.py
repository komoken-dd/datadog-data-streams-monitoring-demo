"""The epoch inventory is intentionally incapable of scaling/deleting resources."""

import importlib.machinery
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


PATH = Path(__file__).resolve().parents[1] / "scripts/demo-epoch-plan"
loader = importlib.machinery.SourceFileLoader("epoch_plan", str(PATH))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)


class EpochPlanTest(unittest.TestCase):
    def fake(self, foreign=False, context="kind-brokerage-demo"):
        calls = []

        def runner(arguments, input_text=None):
            calls.append((arguments, input_text))
            if arguments[1:3] == ["config", "current-context"]:
                return context
            if "exec" in arguments:
                self.assertTrue(input_text.startswith("BEGIN READ ONLY;"))
                self.assertNotIn("TRUNCATE", input_text.upper())
                return json.dumps({"database_bytes": 123, "tables": []})
            kind = arguments[arguments.index("get") + 1]
            if kind == "namespace":
                return json.dumps({"metadata": {"uid": "namespace-1", "labels": {"app.kubernetes.io/part-of": "brokerage-demo"}}})
            if kind == "kafkatopics":
                return json.dumps({"items": [{"metadata": {"name": t.replace(".", "-"), "uid": t,
                    "labels": {"strimzi.io/cluster": "brokerage-kafka"}}, "spec": {"topicName": t}} for t in module.TOPICS]})
            if kind == "pvc":
                return '{"items": []}'
            items = [{"kind": "Deployment", "metadata": {"name": "order-service", "uid": "order"}, "spec": {"replicas": 1}}]
            if foreign:
                items.append({"kind": "Deployment", "metadata": {"name": "unrelated-work", "uid": "foreign"}})
            return json.dumps({"items": items})

        return runner, calls

    @patch.dict("os.environ", {"BROKERAGE_NAMESPACE": "brokerage-demo"})
    def test_default_inventory_only_reads_and_cannot_authorize_reset(self):
        runner, calls = self.fake()
        result = module.inventory("kind", runner)
        self.assertFalse(result["automatic_reset_available"])
        self.assertEqual(result["ownership_blockers"], [])
        self.assertEqual(result["namespace_uid"], "namespace-1")
        for args, _ in calls:
            self.assertFalse(set(args) & {"delete", "apply", "scale", "patch", "replace", "create"})

    def test_wrong_context_stops_before_any_cluster_access(self):
        runner, calls = self.fake(context="production")
        with self.assertRaises(ValueError):
            module.inventory("kind", runner)
        self.assertEqual(len(calls), 1)

    @patch.dict("os.environ", {"BROKERAGE_NAMESPACE": "brokerage-demo"})
    def test_foreign_workload_is_reported_without_modification(self):
        runner, _ = self.fake(foreign=True)
        result = module.inventory("kind", runner)
        self.assertTrue(any("unrelated-work" in item for item in result["ownership_blockers"]))


if __name__ == "__main__":
    unittest.main()
