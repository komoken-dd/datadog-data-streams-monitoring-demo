# Demo Runbook

This runbook covers the local kind and Amazon EKS environments, Datadog
resources, the demo scenarios, and teardown.

## 1. Prerequisites

- Bash on macOS or Linux; on Windows, use WSL2 because the deployment scripts
  do not support native Windows shells
- Podman 6.0 or later (reference path), or Docker with BuildKit after applying
  the [compatibility substitutions](#docker-compatibility-note-not-tested)
- kind, kubectl, and Helm
- curl, jq, OpenSSL, and the GitHub CLI
- Terraform, for the Datadog resources
- JDK 21 only for running `./mvnw verify` on the host; container image builds
  run Maven in a pinned container and do not require a host JDK
- A Datadog organization with API and application keys

The local stack runs ten JVMs, Kafka, PostgreSQL, and the Datadog Agent in one
kind node. Give the Podman machine at least 12 GiB of memory; 16 GiB is the
verified configuration. With 8 GiB the machine thrashes.

```bash
podman machine stop
podman machine set --memory 16384
podman machine start
```

Use a password manager or secret manager to inject `DD_API_KEY`, `DD_APP_KEY`,
and `DD_SITE` into the `scripts/kind-deploy`, `scripts/deploy`, and
`scripts/datadog-terraform` processes. Keep resolved keys outside the
repository and shell history.

## 2. Run locally with kind

Building the images yourself is the currently working local path:

```bash
scripts/kind-up
TARGET=kind scripts/build-images
BROKERAGE_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy
scripts/smoke-test kind
```

- `scripts/kind-up` creates the `brokerage-demo` kind cluster with the Podman
  provider, installs the pinned Strimzi and Datadog Operators, and creates the
  `brokerage-demo` namespace. It is safe to rerun.
- `scripts/build-images` builds all ten services as
  `localhost/brokerage-demo/<service>:0.1.0`. Set `IMAGE_TAG` to use another
  local tag, and pass the same value to `scripts/kind-deploy`.
- `scripts/kind-deploy` loads the local images into kind, validates the
  Datadog site and API/Application Key pair, creates the Kubernetes Secrets,
  installs the Helm chart, and restarts the applications after the Datadog
  admission controller is ready so every JVM starts with the tracer injected.
- `scripts/smoke-test kind` waits for Kafka and every Deployment, submits a
  market BUY of 100 shares of `D101` from account `A00001`, and waits until
  the order is `FILLED` with buying power, trade report, and notification
  reflected for every execution.

After deployment, filter Data Streams Monitoring to `env:local`.

Each deployment, smoke-test, and demo-control script validates the expected
Kubernetes context and namespace before changing the cluster. Confirm that `kubectl config current-context` is
`kind-brokerage-demo` before using the local demo-control commands.

Self-built images are not covered by the repository's GitHub Artifact
Attestations. The person performing the build owns that trust decision.

### Release images

The deployment scripts also support verified multi-architecture GHCR release
images:

```bash
export BROKERAGE_DEMO_RELEASE="vX.Y.Z"
gh auth status
scripts/kind-up
scripts/kind-deploy
scripts/smoke-test kind
```

This path requires a GitHub Release whose image lock contains the ten
brokerage services. No such release has been published yet. The existing
`v0.1.0` release predates the online-brokerage domain and does not contain
these images, so its lock fails validation. Use the self-built path until the
maintainer publishes a new release. See the
[container image policy](container-images.md) for what the release path
verifies.

### Build cache and reproducibility

`scripts/build-images` uses `apps/.m2` as a container-only Maven cache. It is
ignored by Git and separate from the host Maven cache. It may grow over time;
depending on the host volume mapping, its files can also have container-derived
ownership. Delete it when you want to reclaim the space and accept a cold
dependency download on the next image build.

The image build always runs a clean Maven build in the pinned Maven and JDK
container. Spring Boot layers separate stable third-party dependencies from the
application code, and the fixed Maven output timestamp keeps unchanged layer
digests reproducible. To verify both the jar and filesystem layers without
depending on an OS-specific hash command, run:

```bash
scripts/verify-container-build order-service
```

### Docker compatibility note (not tested)

Podman is the reference and tested runtime for this repository. The
`Containerfile` also uses instructions supported by Docker BuildKit, but the
included scripts call Podman directly and do not provide a runtime switch.
Docker users must make the following substitutions in a local copy of the
scripts before running them. Finch and nerdctl are outside this compatibility
note's scope.

| Location | Podman reference | Docker substitution |
|---|---|---|
| `scripts/build-images`, `scripts/kind-up`, and the self-built path in `scripts/kind-deploy` | `require_command podman` and `podman ...` | Require and invoke `docker` instead |
| `scripts/kind-up` | `KIND_EXPERIMENTAL_PROVIDER=podman` | Set the provider to `docker` |
| `scripts/kind-up` | `podman container exists NAME` | Use `docker container inspect NAME >/dev/null 2>&1` |
| `scripts/build-images` | `podman info --format '{{.Host.Arch}}'` | Use `docker info --format '{{.Architecture}}'`; normalize `x86_64`/`amd64` to `amd64` and `aarch64`/`arm64` to `arm64` |
| `scripts/build-images` | `--source-date-epoch "${CONTAINER_SOURCE_DATE_EPOCH}" --rewrite-timestamp` | Remove both Podman-specific flags; do not replace them with `SOURCE_DATE_EPOCH` alone |
| Self-built path in `scripts/kind-deploy` | `podman image exists IMAGE` | Use `docker image inspect IMAGE >/dev/null 2>&1` |
| Self-built path in `scripts/kind-deploy` | `podman save --format docker-archive --multi-image-archive --output ARCHIVE IMAGES...` | Use `docker image save --output ARCHIVE IMAGES...` |
| Self-built path in `scripts/kind-deploy` | `KIND_EXPERIMENTAL_PROVIDER=podman` | Set the provider to `docker` |

Also export the local repository prefix so Docker creates the same image names
expected by `values-kind.yaml` and `scripts/kind-deploy`:

```bash
export REPOSITORY_PREFIX="localhost/brokerage-demo"
```

Enable BuildKit (`DOCKER_BUILDKIT=1` on engines where it is not already the
default). BuildKit's `SOURCE_DATE_EPOCH` build argument does not by itself
rewrite file timestamps inside image layers. That requires the
`rewrite-timestamp=true` image-exporter option available in BuildKit 0.13 or
later. This repository does not document or test the additional buildx
export-and-load or push workflow needed for that option.

Consequently, the Docker path does not guarantee stable filesystem-layer
digests across cache-free builds. `scripts/verify-container-build` verifies the
Podman build path only and must not be translated to Docker. Docker can still
save multiple images in one archive, and kind officially supports the Docker
provider and loading a common image archive.

This compatibility note is based on the documented Docker BuildKit and kind
interfaces. It is not an executed or continuously tested Docker support path:

- [BuildKit reproducible builds](https://github.com/moby/buildkit/blob/master/docs/build-repro.md#source_date_epoch)
- [Docker image save](https://docs.docker.com/reference/cli/docker/image/save/)
- [kind provider and image loading](https://kind.sigs.k8s.io/docs/user/quick-start/#loading-an-image-into-your-cluster)

## 3. Decide whether EKS is appropriate

The EKS path uses the same Helm chart and scripts as kind, but the
online-brokerage services were verified end to end only on kind. Expect to
validate the EKS path yourself.

The AWS stack creates VPC subnets in two Availability Zones, one NAT gateway,
an EKS control plane, and one on-demand `m8g.xlarge` ARM64 worker node with EBS
volumes in one Availability Zone. EKS requires control-plane subnets in at
least two Availability Zones even though this demo deliberately places its
worker in one. The single-AZ worker design reduces cost and is not a
high-availability production topology.

The worker, Kafka broker, PostgreSQL, and application Pods depend on that one
worker Availability Zone. An outage of that zone or worker stops the data plane;
it does not fail over to the second control-plane subnet, and EBS volumes remain
bound to their original zone. This deliberate single-node design minimizes demo
cost; it does not provide worker or Kafka failover.

This is a fresh-stack topology. Do not apply it in place over an earlier
multi-AZ VPC and EKS cluster: EKS cluster subnet updates must retain every
Availability Zone selected when the cluster was created, the managed node group
must be replaced, and existing EBS volumes may be in other zones. Back up any
required data, tear down the old stack deliberately, and create a new stack.

Before deployment:

1. Review current AWS and Datadog pricing.
2. Confirm service quotas and the intended AWS account.
3. Set an external reminder for the intended deletion date.
4. Restrict the EKS public API endpoint to your current administrator CIDR.
5. Review `cdk diff`; do not deploy changes you do not understand.

Nothing is automatically destroyed. Use the teardown commands in section 10
when the environment is no longer needed.

## 4. Create AWS infrastructure

The EKS path additionally requires an authenticated AWS CLI plus Node.js and
npm for AWS CDK.

The CDK stack requires:

| Variable | Purpose |
|---|---|
| `BROKERAGE_DEMO_ADMIN_CIDR` | CIDR allowed to reach the public EKS API, normally one `/32` |
| `BROKERAGE_DEMO_ADMIN_ROLE_ARN` | IAM role granted EKS cluster-admin access |
| `BROKERAGE_DEMO_NODE_INSTANCE_TYPE` | Optional worker type; defaults to `m8g.xlarge`. CDK selects the matching x86_64 or ARM64 EKS AMI automatically. |
| `BROKERAGE_DEMO_AWS_REGION` | Optional AWS region; defaults to `us-west-2` |

```bash
cd infra/aws-cdk
npm ci
npm run build
npx cdk diff
npx cdk deploy
cd ../..
```

CDK does not create image repositories. Private worker nodes use the single NAT
gateway to pull application images from the registry you select, and
reviewed third-party images from their official public registries.

Worker nodes require HTTPS and DNS resolution for Docker Hub, `quay.io`,
`registry.datadoghq.com`, and the application image registry (`ghcr.io` on the
release path). Datadog PrivateLink can carry supported telemetry intake
traffic, but it does not replace access to the Datadog container registry.

Use the stack output to configure kubectl:

```bash
aws eks update-kubeconfig --region <region> --name brokerage-demo
kubectl config current-context
```

## 5. Select application images

### Self-built images for EKS

Until a release with the brokerage images is published, build and push all ten
services to a registry the worker nodes can pull from, as one release unit:

```bash
export REPOSITORY_PREFIX="REGISTRY/NAMESPACE/brokerage-demo"
export IMAGE_TAG="YOUR_IMMUTABLE_TAG"
export BROKERAGE_DEMO_EKS_CONTEXT="EXPECTED_KUBE_CONTEXT"
TARGET=eks PUSH=true scripts/build-images
```

The build writes each registry-returned `tag@digest` reference to the ignored
`deploy/values/values-self-built.json` file. Set
`BROKERAGE_DEMO_IMAGE_VALUES_FILE` to choose another output path. Do not
hand-edit this file. Configure Kubernetes pull credentials separately when
using a private registry. The script detects the selected EKS worker
architecture; do not set a build platform manually.

Self-built EKS references remain digest-pinned. They are not signed by this
repository's release workflow or verifiable with its GitHub Artifact
Attestations. The person performing the build owns that trust decision.

### Release images

Once a release containing the brokerage images exists, select it and
authenticate the GitHub CLI so the deploy script can verify the release image
lock's Artifact Attestation:

```bash
export BROKERAGE_DEMO_RELEASE="vX.Y.Z"
gh auth status
```

Publishing a release builds all ten services from the tagged source, pushes a
semantic release tag to public GHCR, and generates SBOM, BuildKit provenance,
and GitHub Artifact Attestations. The workflow also adds
`brokerage-demo-images-<release>.json` to the GitHub Release. That lock
contains one `tag@sha256` reference per service and is passed directly to
Helm.

The deploy script downloads the public lock, validates its schema and all ten
GHCR references, and verifies that it was signed by this repository's
`release-images.yml` workflow for the selected tag and source revision. No
registry login, local application build, or image copy is required. See the
[container image policy](container-images.md) for the image inventory and
trust boundaries.

## 6. Provide credentials safely

The deployment and Terraform commands expect these values in their process
environment:

- `DD_API_KEY`
- `DD_APP_KEY`
- `DD_SITE`
- `POSTGRES_PASSWORD` on EKS when the `brokerage-demo-postgresql` Secret does
  not already exist (kind generates a random local password)

Inject secret values for each command from your password manager or secret
manager. Do not place them in this repository, an `.env` file, Terraform
variables, Helm values, command examples, or shell history.

The default deployment path creates these Kubernetes Secrets:

| Namespace | Secret | Keys |
|---|---|---|
| `datadog` | `datadog-secret` | `api-key`, `app-key` |
| `brokerage-demo` | `brokerage-demo-postgresql` | `password` |

On EKS, to provision the Datadog Secret from AWS Secrets Manager or another
system, set `BROKERAGE_DEMO_CREATE_K8S_SECRETS=false`; the deployment script
then verifies that it already exists. `BROKERAGE_DEMO_DATADOG_SECRET_NAME`
selects a Secret name other than `datadog-secret`. Provision
`brokerage-demo-postgresql` separately before deployment if PostgreSQL
credentials are also managed externally. The script preserves that Secret when
it already exists; otherwise it creates it from `POSTGRES_PASSWORD`.

## 7. Deploy to EKS

Set non-secret deployment context values and run the deployment inside the
credential-injected process:

```bash
export BROKERAGE_DEMO_AWS_ACCOUNT_ID="EXPECTED_12_DIGIT_ACCOUNT_ID"
export BROKERAGE_DEMO_EKS_CONTEXT="EXPECTED_KUBE_CONTEXT"

BROKERAGE_DEMO_IMAGE_SOURCE=self-built scripts/deploy eks
scripts/smoke-test eks
```

With a published release, export `BROKERAGE_DEMO_RELEASE` and run
`scripts/deploy eks` without `BROKERAGE_DEMO_IMAGE_SOURCE`.

On the self-built path, the script validates the exact ten-service set and
every `tag@digest` reference in `deploy/values/values-self-built.json` and
deliberately skips GitHub Artifact Attestation verification. On the release
path, it verifies the release lock and its Attestation before mutation. On
both paths, it verifies the active Kubernetes context, the active AWS account
against `BROKERAGE_DEMO_AWS_ACCOUNT_ID`, the Datadog site, and the
API/Application Key pair. It passes the selected image values to Helm so each
application uses its exact `tag@digest` reference and `DD_VERSION` uses the
selected release-unit tag. PostgreSQL, Strimzi, and Datadog use the reviewed
official-registry references in the chart. After rollout, the script confirms
that all application images match the selected references and that every
runtime image includes a digest.

After deployment, filter Data Streams Monitoring to `env:demo`.

## 8. Create Datadog resources

Run the mock-provider regression tests independently of Datadog credentials,
variable files, and deployment state:

```bash
scripts/datadog-terraform test
```

Copy the non-secret example file:

```bash
cp infra/datadog-terraform/example.tfvars \
  infra/datadog-terraform/local.auto.tfvars
```

Replace:

- `datadog_ui_base_url` with the organization UI origin
- `datadog_site` with the same value as `DD_SITE`
- `environment` with `local` for kind or `demo` for the supplied EKS values

The environment has no default. A dashboard is intentionally tied to one
environment because its Workflow connections, when enabled, target one concrete
cluster.

Choose one control mode:

- Keep `enable_workflows = false` and leave all five connection IDs as `null`.
  The dashboard, monitors, metric configuration, and APM retention filter are
  created, and all actions remain available through `scripts/demo-control`.
- Follow [Agent-based Private Action Runner setup](private-action-runner.md),
  set `enable_workflows = true`, and replace all five connection IDs with the
  resulting Private HTTP Connection UUIDs. This also creates the seven
  dashboard Workflow controls.

Then run:

```bash
scripts/datadog-terraform init
scripts/datadog-terraform validate
scripts/datadog-terraform plan
scripts/datadog-terraform apply
scripts/datadog-terraform output
```

With workflows disabled, the plan creates 17 resources: the dashboard, three
monitors, four distribution tag configurations, eight metric metadata entries,
and the APM retention filter. `output` prints the dashboard URL.

The four `datadog_metric_tag_configuration` resources apply to their
demo-specific metric names across the Datadog organization, not only to this
dashboard. Three of them enable the p50/p95/p99 percentiles used by the
dashboard and the reflection-delay monitor; those widgets stay empty until
apply. The APM
retention filter retains all matching demo order spans, and the Agent collects
all container logs. Review the corresponding custom-metric, indexed-span, and
log-ingestion usage before leaving the demo running.

### Separate state per environment

By default the wrapper uses ignored local state in
`infra/datadog-terraform/.terraform-state/terraform.tfstate` and the variable
file `infra/datadog-terraform/local.auto.tfvars`. To keep a separate state and
dashboard per environment, for example when an organization already has
resources from another deployment, point both at environment-specific files
with absolute paths:

```bash
export BROKERAGE_DEMO_TF_STATE="${PWD}/infra/datadog-terraform/.terraform-state/kind.tfstate"
export BROKERAGE_DEMO_TFVARS="${PWD}/infra/datadog-terraform/kind.tfvars"
scripts/datadog-terraform plan
scripts/datadog-terraform apply
```

Keep the same two variables set for every later `plan`, `apply`, `output`, and
destroy command against that environment. Both example paths are ignored by
Git. When multiple operators need the same Terraform state, configure an
encrypted remote backend with access controls and locking instead of using the
local-state wrapper unchanged.

## Optional: show the full DSM consumer topology

The default DSM map omits `order-state-service`, whose projection consumes all
nine topics and can obscure the primary business path. The
`exception-management-service` pathway remains enabled, including redrive. To
include the state projection as well, set the following when running either
deployment command and keep the same setting for later redeployments:

```bash
export BROKERAGE_DEMO_DSM_FULL_TOPOLOGY=true
```

Unset it to return to the primary-flow map. See the
[Datadog learning guide](datadog-learning-guide.md) before comparing the two
views.

## 9. Run the scenarios

Use the dashboard button when workflows are enabled, or run the equivalent CLI
command. The CLI verifies the Kubernetes context, opens a temporary
port-forward to the ClusterIP control endpoint, and prints the JSON status
returned by each call. For EKS, export `BROKERAGE_DEMO_EKS_CONTEXT` first.

```bash
scripts/demo-control kind baseline
scripts/demo-control kind opening-rush
scripts/demo-control kind buying-power-delay
scripts/demo-control kind push-outage
scripts/demo-control kind redrive
scripts/demo-control kind sell-off
scripts/demo-control kind stop
```

| Action | Effect | Ends |
|---|---|---|
| `baseline` | Baseline order flow, exchange reset, buying-power and push-provider recovery | Immediately |
| `opening-rush` | 60-second pre-open at 25 orders/s, then one opening-auction burst | Returns to baseline automatically |
| `buying-power-delay` | 2,500 ms extra processing per execution in `buying-power-service` | Until `baseline` |
| `push-outage` | Push gateway returns 503; notifications move through retries to the DLT | Until `baseline` |
| `redrive` | Re-sends every unresolved notification dead letter | Immediately; run after `baseline` |
| `sell-off` | 120 seconds of extra `D105` sell orders down to limit-down | Order flow returns to baseline automatically; `D105` stays at limit-down until `baseline` |
| `stop` | Stops new orders; accepted orders continue | Until `baseline` |

Expected effects appear within tens of seconds. See
[Demo scenarios and presentation steps](demo-presentation-steps.md) for what to
observe in DSM and on the dashboard in each scenario, with approximate values
from a local run.

Run `stop` after the demonstration to stop new order intake.

## 10. Teardown

Destroy Datadog resources first. Keep `BROKERAGE_DEMO_TF_STATE` and
`BROKERAGE_DEMO_TFVARS` set if you used them for this environment.

```bash
scripts/datadog-terraform destroy-plan
CONFIRM_DATADOG_DESTROY=brokerage-demo scripts/datadog-terraform destroy-apply
```

Delete the local kind cluster:

```bash
KIND_EXPERIMENTAL_PROVIDER=podman kind delete cluster --name brokerage-demo
```

Review and destroy AWS resources:

```bash
cd infra/aws-cdk
npx cdk diff
npx cdk destroy
```

Finally, verify in the AWS and Datadog consoles that resources, volumes, load
balancers, log retention, and billable telemetry no longer remain.
