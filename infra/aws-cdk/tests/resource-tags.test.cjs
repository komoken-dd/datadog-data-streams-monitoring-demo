const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const cdk = require("aws-cdk-lib");
const { Template } = require("aws-cdk-lib/assertions");
const { BrokerageDemoStack } = require("../lib/brokerage-demo-stack");

function withStack(tags, check) {
  const selected = {
    BROKERAGE_DEMO_ADMIN_CIDR: "192.0.2.1/32",
    BROKERAGE_DEMO_ADMIN_ROLE_ARN: "arn:aws:iam::123456789012:role/test-operator",
    BROKERAGE_DEMO_NODE_INSTANCE_TYPE: "m8g.xlarge",
    BROKERAGE_DEMO_RESOURCE_TAGS: tags,
  };
  const previous = Object.fromEntries(Object.keys(selected).map((key) => [key, process.env[key]]));
  const outdir = fs.mkdtempSync(path.join(os.tmpdir(), "brokerage-tag-test-"));
  try {
    for (const [key, value] of Object.entries(selected)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    const app = new cdk.App({
      outdir,
      context: {
        "availability-zones:account=123456789012:region=us-west-2": ["us-west-2a", "us-west-2b"],
      },
    });
    const stack = new BrokerageDemoStack(app, "TagTest", {
      env: { account: "123456789012", region: "us-west-2" },
    });
    check(Template.fromStack(stack));
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    fs.rmSync(outdir, { recursive: true, force: true });
  }
}

test("literal tags reach the EKS cluster, node group, EC2 instances and root volumes", () => {
  withStack(" example.com/owner = Demo Team ,cost:center=Research=One,owner=old,owner=new,project=ignored", (template) => {
    const expected = {
      "example.com/owner": "Demo Team",
      "cost:center": "Research=One",
      owner: "new",
      project: "datadog-data-streams-monitoring-demo",
    };
    const resource = (type) => {
      const resources = Object.values(template.findResources(type));
      assert.equal(resources.length, 1);
      return resources[0].Properties;
    };
    assert.deepEqual(resource("Custom::AWSCDK-EKS-Cluster").Config.tags, expected);
    assert.deepEqual(resource("AWS::EKS::Nodegroup").Tags, expected);
    const specifications = resource("AWS::EC2::LaunchTemplate").LaunchTemplateData.TagSpecifications;
    assert.deepEqual(specifications.map((item) => item.ResourceType).sort(), ["instance", "volume"]);
    for (const item of specifications) {
      assert.deepEqual(Object.fromEntries(item.Tags.map(({ Key, Value }) => [Key, Value])), expected);
    }
  });
});

test("unset and empty tag input preserve the default project tag", () => {
  for (const input of [undefined, "", " , , "]) {
    withStack(input, (template) => {
      template.hasResourceProperties("Custom::AWSCDK-EKS-Cluster", {
        Config: { tags: { project: "datadog-data-streams-monitoring-demo" } },
      });
    });
  }
});

test("malformed and whitespace-only tag keys or values are rejected", () => {
  for (const input of ["owner", "=alice", "owner=", " =alice", "owner=   "]) {
    assert.throws(() => withStack(input, () => {}), /entries must be key=value/);
  }
});
