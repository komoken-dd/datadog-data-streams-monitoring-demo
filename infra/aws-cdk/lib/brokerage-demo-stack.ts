import * as cdk from "aws-cdk-lib";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as eks from "aws-cdk-lib/aws-eks";
import * as iam from "aws-cdk-lib/aws-iam";
import { KubectlV36Layer } from "@aws-cdk/lambda-layer-kubectl-v36";
import { Construct } from "constructs";

export class BrokerageDemoStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const adminCidr = requiredEnvironment("BROKERAGE_DEMO_ADMIN_CIDR");
    const operatorRoleArn = requiredEnvironment("BROKERAGE_DEMO_ADMIN_ROLE_ARN");
    const nodeInstanceType = new ec2.InstanceType(
      process.env.BROKERAGE_DEMO_NODE_INSTANCE_TYPE ?? "m8g.xlarge",
    );
    const nodeArchitecture = nodeInstanceType.architecture;
    const nodeAmiType = nodeArchitecture === ec2.InstanceArchitecture.ARM_64
      ? eks.NodegroupAmiType.AL2023_ARM_64_STANDARD
      : eks.NodegroupAmiType.AL2023_X86_64_STANDARD;
    // Preserve the existing x86_64 physical names while giving ARM64
    // deployments names that accurately describe their worker architecture.
    const nodeResourceName = nodeArchitecture === ec2.InstanceArchitecture.ARM_64
      ? "brokerage-demo-arm64"
      : "brokerage-demo-x86";

    if (!/^arn:aws:iam::\d{12}:role\/.+/.test(operatorRoleArn)) {
      throw new Error("BROKERAGE_DEMO_ADMIN_ROLE_ARN must be an IAM role ARN");
    }

    // Organization-specific tags (owner, cost center, retention) come from the
    // environment so they are applied to resources that do not inherit stack tags.
    const projectTags: Record<string, string> = {
      ...additionalTags(process.env.BROKERAGE_DEMO_RESOURCE_TAGS),
      project: "datadog-data-streams-monitoring-demo",
    };

    for (const [key, value] of Object.entries(projectTags)) {
      cdk.Tags.of(this).add(key, value);
    }

    const vpc = new ec2.Vpc(this, "Vpc", {
      vpcName: "brokerage-demo",
      ipAddresses: ec2.IpAddresses.cidr("10.80.0.0/16"),
      // EKS requires cluster subnets in at least two Availability Zones even
      // when the demo worker nodes themselves are intentionally single-AZ.
      maxAzs: 2,
      natGateways: 1,
      subnetConfiguration: [
        {
          name: "public",
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: "workloads",
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 20,
        },
      ],
    });

    const cluster = new eks.Cluster(this, "Cluster", {
      clusterName: "brokerage-demo",
      tags: projectTags,
      version: eks.KubernetesVersion.V1_36,
      vpc,
      vpcSubnets: [{ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }],
      defaultCapacity: 0,
      endpointAccess: eks.EndpointAccess.PUBLIC_AND_PRIVATE.onlyFrom(adminCidr),
      authenticationMode: eks.AuthenticationMode.API,
      clusterLogging: [
        eks.ClusterLoggingTypes.API,
        eks.ClusterLoggingTypes.AUDIT,
        eks.ClusterLoggingTypes.AUTHENTICATOR,
        eks.ClusterLoggingTypes.CONTROLLER_MANAGER,
        eks.ClusterLoggingTypes.SCHEDULER,
      ],
      kubectlLayer: new KubectlV36Layer(this, "KubectlLayer"),
    });

    // The EKS L2 does not expose upgradePolicy yet. Override the create request
    // so the demo never opts into Extended Support implicitly.
    const clusterCustomResource = cluster.node
      .findChild("Resource")
      .node.findChild("Resource")
      .node.defaultChild;
    if (!(clusterCustomResource instanceof cdk.CfnResource)) {
      throw new Error("Unable to locate the EKS cluster custom resource");
    }
    clusterCustomResource.addPropertyOverride("Config.upgradePolicy", {
      supportType: "STANDARD",
    });

    cluster.grantAccess("OperatorClusterAdmin", operatorRoleArn, [
      eks.AccessPolicy.fromAccessPolicyName("AmazonEKSClusterAdminPolicy", {
        accessScopeType: eks.AccessScopeType.CLUSTER,
      }),
    ]);

    const nodeResourceTags = Object.entries(projectTags).map(([key, value]) => ({ key, value }));
    const nodeLaunchTemplate = new ec2.CfnLaunchTemplate(this, "DemoNodeLaunchTemplate", {
      launchTemplateName: nodeResourceName,
      launchTemplateData: {
        blockDeviceMappings: [{
          deviceName: "/dev/xvda",
          ebs: {
            deleteOnTermination: true,
            encrypted: true,
            volumeSize: 100,
            volumeType: "gp3",
          },
        }],
        metadataOptions: {
          httpEndpoint: "enabled",
          httpTokens: "required",
        },
        tagSpecifications: [
          { resourceType: "instance", tags: nodeResourceTags },
          { resourceType: "volume", tags: nodeResourceTags },
        ],
      },
    });

    // One worker is intentional: this cost-conscious demo does not require
    // worker-node or Kafka failover.
    cluster.addNodegroupCapacity("DemoNodes", {
      nodegroupName: nodeResourceName,
      instanceTypes: [nodeInstanceType],
      amiType: nodeAmiType,
      minSize: 1,
      desiredSize: 1,
      maxSize: 1,
      subnets: { subnets: [vpc.privateSubnets[0]] },
      capacityType: eks.CapacityType.ON_DEMAND,
      tags: projectTags,
      launchTemplateSpec: {
        id: nodeLaunchTemplate.ref,
        version: nodeLaunchTemplate.attrLatestVersionNumber,
      },
    });

    const ebsCsiRole = new iam.CfnRole(this, "EbsCsiRole", {
      roleName: "brokerage-demo-ebs-csi",
      assumeRolePolicyDocument: {
        Version: "2012-10-17",
        Statement: [{
          Effect: "Allow",
          Principal: { Service: "pods.eks.amazonaws.com" },
          Action: ["sts:AssumeRole", "sts:TagSession"],
        }],
      },
      managedPolicyArns: [
        "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy",
      ],
    });

    const ebsPodIdentity = new eks.CfnPodIdentityAssociation(this, "EbsCsiPodIdentity", {
      clusterName: cluster.clusterName,
      namespace: "kube-system",
      serviceAccount: "ebs-csi-controller-sa",
      roleArn: ebsCsiRole.attrArn,
    });

    const podIdentityAgentAddon = new eks.CfnAddon(this, "PodIdentityAgentAddon", {
      addonName: "eks-pod-identity-agent",
      addonVersion: "v1.3.10-eksbuild.3",
      clusterName: cluster.clusterName,
      resolveConflicts: "OVERWRITE",
    });
    ebsPodIdentity.addResourceDependency(podIdentityAgentAddon);

    const ebsCsiAddon = new eks.CfnAddon(this, "EbsCsiAddon", {
      addonName: "aws-ebs-csi-driver",
      addonVersion: "v1.63.1-eksbuild.1",
      clusterName: cluster.clusterName,
      resolveConflicts: "OVERWRITE",
    });
    ebsCsiAddon.addResourceDependency(ebsPodIdentity);

    new cdk.CfnOutput(this, "ClusterName", { value: cluster.clusterName });
    new cdk.CfnOutput(this, "NodeArchitecture", { value: nodeArchitecture });
    new cdk.CfnOutput(this, "ConfigureKubectl", {
      value: `aws eks update-kubeconfig --region ${this.region} --name ${cluster.clusterName}`,
    });
  }
}

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Required environment variable is unset: ${name}`);
  }
  return value;
}

function additionalTags(value: string | undefined): Record<string, string> {
  const tags: Record<string, string> = {};
  for (const pair of (value ?? "").split(",").map((item) => item.trim()).filter(Boolean)) {
    const separator = pair.indexOf("=");
    const key = pair.slice(0, separator).trim();
    const tagValue = pair.slice(separator + 1).trim();
    if (separator < 0 || !key || !tagValue) {
      throw new Error(`BROKERAGE_DEMO_RESOURCE_TAGS entries must be key=value: ${pair}`);
    }
    tags[key] = tagValue;
  }
  return tags;
}
