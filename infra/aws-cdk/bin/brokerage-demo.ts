#!/usr/bin/env node
import * as cdk from "aws-cdk-lib";
import { BrokerageDemoStack } from "../lib/brokerage-demo-stack";

const app = new cdk.App();

new BrokerageDemoStack(app, "BrokerageDemoStack", {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.BROKERAGE_DEMO_AWS_REGION ?? "us-west-2",
  },
  description: "Datadog Data Streams Monitoring demo on Amazon EKS",
});
