"""CloudFormation handler tests: existing groups, exact verification, safe delete."""

import importlib.util
from pathlib import Path
import types
import unittest


HANDLER = Path(__file__).resolve().parents[1] / "infra/aws-cdk/lib/log-group-settings/handler.py"
spec = importlib.util.spec_from_file_location("log_group_settings", HANDLER)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AlreadyExists(Exception):
    pass


class FakeLogs:
    exceptions = types.SimpleNamespace(ResourceAlreadyExistsException=AlreadyExists)

    def __init__(self, existing=False, verify=True):
        self.groups = {"/aws/eks/brokerage-demo/cluster": {"tags": {"preserve": "existing"}}} if existing else {}
        self.verify = verify
        self.creates = []

    def create_log_group(self, logGroupName, tags):
        self.creates.append((logGroupName, tags.copy()))
        if logGroupName in self.groups:
            raise AlreadyExists()
        self.groups[logGroupName] = {"tags": tags.copy()}

    def name(self, arn):
        return arn.split(":log-group:", 1)[1]

    def tag_resource(self, resourceArn, tags):
        self.groups[self.name(resourceArn)]["tags"].update(tags)

    def put_retention_policy(self, logGroupName, retentionInDays):
        self.groups[logGroupName]["retention"] = retentionInDays

    def list_tags_for_resource(self, resourceArn):
        return {"tags": self.groups[self.name(resourceArn)]["tags"] if self.verify else {}}

    def describe_log_groups(self, logGroupNamePrefix):
        return {"logGroups": [{"logGroupName": name, "retentionInDays": values.get("retention")}
                              for name, values in self.groups.items() if name.startswith(logGroupNamePrefix)]}


class LogGroupSettingsTest(unittest.TestCase):
    def event(self, request_type="Create"):
        return {"RequestType": request_type, "ResourceProperties": {
            "LogGroupNames": ["/aws/eks/brokerage-demo/cluster"],
            "Tags": {"example.com/owner": "Demo Team", "cost:center": "Research=One"},
            "RetentionDays": "7", "Partition": "aws", "Region": "us-west-2", "Account": "123456789012",
        }}

    def test_new_group_is_tagged_at_creation_and_verified(self):
        client = FakeLogs()
        response = module.on_event(self.event(), None, client)
        self.assertEqual(client.creates[0][1], self.event()["ResourceProperties"]["Tags"])
        self.assertEqual(response["Data"]["VerifiedLogGroups"], 1)

    def test_existing_group_is_reconciled_without_replacing_or_removing_other_tags(self):
        client = FakeLogs(existing=True)
        module.on_event(self.event("Update"), None, client)
        group = client.groups["/aws/eks/brokerage-demo/cluster"]
        self.assertEqual(group["retention"], 7)
        self.assertEqual(group["tags"]["preserve"], "existing")
        self.assertEqual(group["tags"]["example.com/owner"], "Demo Team")

    def test_deployment_fails_if_actual_tags_are_not_verified(self):
        with self.assertRaisesRegex(RuntimeError, "not verified"):
            module.on_event(self.event(), None, FakeLogs(verify=False), sleep=lambda _: None)

    def test_delete_retains_existing_audit_logs_and_settings(self):
        client = FakeLogs(existing=True)
        module.on_event(self.event("Delete"), None, client)
        self.assertEqual(len(client.groups), 1)
        self.assertEqual(client.creates, [])


if __name__ == "__main__":
    unittest.main()
