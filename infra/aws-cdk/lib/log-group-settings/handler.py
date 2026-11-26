"""Reconcile tags and retention on service-created CloudWatch log groups.

Unlike a new AWS::Logs::LogGroup with the same name, this also safely adopts
the settings of an already-running EKS/CDK deployment without recreating logs.
The groups themselves are retained on deletion of this settings resource.
"""

import time


def on_event(event, context, logs_client=None, sleep=time.sleep):
    physical_id = event.get("PhysicalResourceId", "brokerage-demo-log-group-settings")
    if event["RequestType"] == "Delete":
        return {"PhysicalResourceId": physical_id}
    if logs_client is None:
        import boto3
        logs_client = boto3.client("logs")
    properties = event["ResourceProperties"]
    tags = properties["Tags"]
    retention = int(properties["RetentionDays"])
    partition = properties["Partition"]
    region = properties["Region"]
    account = properties["Account"]
    for name in properties["LogGroupNames"]:
        arn = f"arn:{partition}:logs:{region}:{account}:log-group:{name}"
        try:
            logs_client.create_log_group(logGroupName=name, tags=tags)
        except logs_client.exceptions.ResourceAlreadyExistsException:
            pass
        logs_client.tag_resource(resourceArn=arn, tags=tags)
        logs_client.put_retention_policy(logGroupName=name, retentionInDays=retention)
        # Verify actual service resources, accounting for eventual consistency.
        for attempt in range(6):
            actual = logs_client.list_tags_for_resource(resourceArn=arn).get("tags", {})
            groups = logs_client.describe_log_groups(logGroupNamePrefix=name).get("logGroups", [])
            group = next((g for g in groups if g["logGroupName"] == name), {})
            if all(actual.get(k) == v for k, v in tags.items()) and group.get("retentionInDays") == retention:
                break
            if attempt == 5:
                raise RuntimeError("CloudWatch log group settings were not verified")
            sleep(2)
    return {"PhysicalResourceId": physical_id, "Data": {"VerifiedLogGroups": len(properties["LogGroupNames"])}}
