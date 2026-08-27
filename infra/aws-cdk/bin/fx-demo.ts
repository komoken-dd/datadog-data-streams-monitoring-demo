#!/usr/bin/env node
import * as cdk from "aws-cdk-lib";
import { FxDemoStack } from "../lib/fx-demo-stack";

const app = new cdk.App();

new FxDemoStack(app, "FxDemoStack", {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.FX_DEMO_AWS_REGION ?? "us-west-2",
  },
  description: "Datadog Data Streams Monitoring demo on Amazon EKS",
});
