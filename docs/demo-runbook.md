# Demo Runbook

This runbook covers both the local kind and Amazon EKS environments,
Datadog resources, the demo sequence, and teardown.

## 1. Local validation

Prerequisites:

- Bash on macOS or Linux; on Windows, use WSL2 because the deployment scripts
  do not support native Windows shells
- Podman 6.0 or later (reference path), or Docker with BuildKit after applying
  the compatibility substitutions below
- kind
- kubectl
- Helm
- curl, jq, OpenSSL, and the GitHub CLI
- JDK 21 only for running `./mvnw verify` on the host; container image builds
  do not require a host JDK
- A Datadog organization with API and application keys

Use a password manager or secret manager to inject `DD_API_KEY`, `DD_APP_KEY`,
and `DD_SITE` into the `scripts/kind-deploy` process.
Keep resolved keys outside the repository and shell history.

```bash
cd apps
./mvnw verify
cd ..

export FX_DEMO_RELEASE="v0.1.0"
gh auth status
scripts/kind-up
scripts/kind-deploy
scripts/smoke-test kind
```

The recommended kind path verifies the published release image lock and its
GitHub Artifact Attestation, then pulls the same immutable GHCR application
references used by EKS.

To build the applications yourself instead, replace the deployment portion
above with:

```bash
scripts/kind-up
export IMAGE_TAG="YOUR_LOCAL_IMAGE_TAG"
scripts/build-images
FX_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy
scripts/smoke-test kind
```

The self-built kind path loads all ten local images into the cluster. Those
images are not covered by the published release's GitHub Artifact Attestations.

The deploy script validates the target
Datadog site and API/Application Key pair before creating the local Kubernetes
Secret.
After deployment, filter Data Streams Monitoring to `env:local`.

Confirm that `kubectl config current-context` is `kind-fx-demo` before using
the local demo-control commands. Each script validates the expected context and
namespace before changing the cluster.

On the self-built path, `scripts/build-images` uses `apps/.m2` as a
container-only Maven cache. It is
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
export REPOSITORY_PREFIX="localhost/fx-demo"
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

## 2. Decide whether EKS is appropriate

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

## 3. Create AWS infrastructure

The EKS path additionally requires an authenticated AWS CLI plus Node.js and
npm for AWS CDK. The recommended release-image path also requires the GitHub
CLI for Artifact Attestation verification.

The CDK stack requires:

| Variable | Purpose |
|---|---|
| `FX_DEMO_ADMIN_CIDR` | CIDR allowed to reach the public EKS API, normally one `/32` |
| `FX_DEMO_ADMIN_ROLE_ARN` | IAM role granted EKS cluster-admin access |
| `FX_DEMO_NODE_INSTANCE_TYPE` | Optional worker type; defaults to `m8g.xlarge`. CDK selects the matching x86_64 or ARM64 EKS AMI automatically. |
| `FX_DEMO_AWS_REGION` | Optional AWS region; defaults to `us-west-2` |

```bash
cd infra/aws-cdk
npm ci
npm run build
npx cdk diff
npx cdk deploy
cd ../..
```

CDK does not create image repositories. Private worker nodes use the single NAT
gateway to pull the recommended application images from public GHCR, or
optional self-built images from the registry selected by the user. Reviewed
third-party images come from their official public registries.

The recommended path requires HTTPS and DNS resolution for `ghcr.io`, Docker
Hub, `quay.io`, and `registry.datadoghq.com`; a self-built path also requires
access to its selected registry. Datadog PrivateLink can
carry supported telemetry intake traffic, but it does not replace access to
the Datadog container registry.

Use the stack output to configure kubectl:

```bash
aws eks update-kubeconfig --region <region> --name fx-demo
kubectl config current-context
```

## 4. Select application images

For both kind and EKS, the recommended source is the public
multi-architecture application release published by this repository. Select a
semantic release and authenticate the GitHub CLI so the deploy script can
verify the release image lock's Artifact Attestation:

```bash
export FX_DEMO_RELEASE="v0.1.0"
gh auth status
```

Publishing a release builds all ten services from the tagged source, pushes a
semantic release tag to public GHCR, and generates SBOM, BuildKit provenance,
and GitHub Artifact Attestations. The workflow also adds
`fx-demo-images-<release>.json` to the GitHub Release. That lock contains one
`tag@sha256` reference per service and is passed directly to Helm.

The deploy script downloads the public lock, validates its schema and all ten
GHCR references, and verifies that it was signed by this repository's
`release-images.yml` workflow for the selected tag and source revision. No
registry login, local application build, ECR repository, or image copy is
required. See the [container image policy](container-images.md) for the image
inventory and trust boundaries.

If you need to inspect, modify, and build the images yourself, the same
deployment scripts provide an explicit `self-built` path:

- For kind, run `scripts/build-images`, then deploy with
  `FX_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy`. The script loads the
  local images into kind.
- For EKS, choose a registry the worker nodes can pull from and run all ten
  builds and pushes as one release unit:

  ```bash
  export REPOSITORY_PREFIX="REGISTRY/NAMESPACE/fx-demo"
  export IMAGE_TAG="YOUR_IMMUTABLE_TAG"
  export FX_DEMO_EKS_CONTEXT="EXPECTED_KUBE_CONTEXT"
  TARGET=eks PUSH=true scripts/build-images
  ```

  The build writes each registry-returned `tag@digest` reference to the ignored
  `deploy/values/values-self-built.json` file. Set
  `FX_DEMO_IMAGE_VALUES_FILE` to choose another output path. Do not hand-edit
  this file. Configure Kubernetes pull credentials separately when using a
  private registry. The script detects the selected EKS worker architecture;
  do not set a build platform manually.

Self-built EKS references remain digest-pinned; local kind images are selected
by the exact local tag and loaded directly. Neither is signed by this
repository's release workflow or verifiable with its GitHub Artifact
Attestations. The person performing the build owns that trust decision.

## 5. Provide credentials safely

The deployment and Terraform commands expect these values in their process
environment:

- `DD_API_KEY`
- `DD_APP_KEY`
- `DD_SITE`
- `POSTGRES_PASSWORD` when the `fx-demo-postgresql` Secret does not already exist

Inject secret values for each command from your password manager or secret
manager. Do not place them in this repository, an `.env` file, Terraform
variables, Helm values, command examples, or shell history.

The default deployment path creates these Kubernetes Secrets:

| Namespace | Secret | Keys |
|---|---|---|
| `datadog` | `datadog-secret` | `api-key`, `app-key` |
| `fx-demo` | `fx-demo-postgresql` | `password` |

To provision the Datadog Secret from AWS Secrets Manager or another system, set
`FX_DEMO_CREATE_K8S_SECRETS=false`; the deployment script then verifies that it
already exists. Provision `fx-demo-postgresql` separately before deployment if
PostgreSQL credentials are also managed externally. The script preserves that
Secret when it already exists; otherwise it creates it from `POSTGRES_PASSWORD`.

## 6. Deploy to EKS

Set non-secret deployment context values and run the deployment inside the
credential-injected process:

```bash
export FX_DEMO_AWS_ACCOUNT_ID="EXPECTED_12_DIGIT_ACCOUNT_ID"
export FX_DEMO_EKS_CONTEXT="EXPECTED_KUBE_CONTEXT"
export FX_DEMO_RELEASE="v0.1.0"

scripts/deploy eks
scripts/smoke-test eks
```

On the default release path, the deployment verifies the release lock and its
GitHub Artifact Attestation before mutation. On both image paths, it verifies
the active Kubernetes context, the active AWS account against
`FX_DEMO_AWS_ACCOUNT_ID`, the Datadog site, and API/Application Key pair. It
passes the selected image values to Helm so each application uses its exact
`tag@digest` reference and `DD_VERSION` uses the selected release-unit tag.
PostgreSQL, Strimzi, and Datadog use the reviewed official-registry
references in the chart. After rollout, the script confirms that all
application images match the selected references and that every runtime image
includes a digest.

To deploy images produced by the optional self-build command in section 4,
keep the same AWS and Datadog environment variables and run:

```bash
FX_DEMO_IMAGE_SOURCE=self-built scripts/deploy eks
scripts/smoke-test eks
```

The deploy script validates the exact ten-service set and every `tag@digest`
reference in `deploy/values/values-self-built.json`. This path deliberately
skips GitHub Artifact Attestation verification because the images were not
produced by the repository's release workflow.

## 7. Create Datadog resources

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
cluster. To operate kind and EKS concurrently in the same Datadog organization,
use a separate variable file, Terraform state, and dashboard for each target.

Choose one control mode:

- Keep `enable_workflows = false` and leave all three connection IDs as `null`.
  The dashboard, monitors, and APM retention filter are created, and all six
  actions remain available through `scripts/demo-control`.
- Follow [Agent-based Private Action Runner setup](private-action-runner.md),
  set `enable_workflows = true`, and replace all three connection IDs with the
  resulting Private HTTP Connection UUIDs. This also creates the six Dashboard
  Workflow controls.

Then run:

```bash
scripts/datadog-terraform init
scripts/datadog-terraform validate
scripts/datadog-terraform plan
scripts/datadog-terraform apply
scripts/datadog-terraform output
```

The three `datadog_metric_tag_configuration` resources apply to their
demo-specific metric names across the Datadog organization, not only to this
dashboard. The APM retention filter retains all matching demo order spans, and
the Agent collects all container logs. Review the corresponding custom-metric,
indexed-span, and log-ingestion usage before leaving the demo running.

The wrapper uses ignored local state by default. When multiple operators need
the same Terraform state, configure an encrypted remote backend with access
controls and locking instead of using the local-state wrapper unchanged.

## Optional: show the full DSM consumer topology

The default DSM map omits `order-state-service`, whose projection consumes all
lifecycle topics and can obscure the primary business path. The
`dead-letter-service` pathway remains enabled, including redrive. To include the
state projection as well, set the following when running either deployment
command and keep the same setting for later redeployments:

```bash
export FX_DEMO_DSM_FULL_TOPOLOGY=true
```

Unset it to return to the primary-flow map. See the
[Datadog learning guide](datadog-learning-guide.md) before comparing the two
views.

## 8. Core 15-minute demo

Use the Dashboard button when workflows are enabled, or run the equivalent CLI
command (`scripts/demo-control <kind|eks> <action>`).

1. Run **Reset to Baseline** (`baseline`).
2. Open **Demo Overview** and Data Streams Monitoring.
3. Explain the main Kafka path and the executed-trade fan-out.
4. Run **Inject Execution Delay** (`delay`).
5. Show pending orders, pending fictional USD-equivalent notional, and
   order-to-execution p95/p99 increasing.
6. In DSM, identify lag on the approved-order execution pathway.
7. Use APM and logs to inspect the execution adapter and one `order_id`.
8. Run **Reset to Baseline** (`baseline`) and watch the backlog drain.
9. Run **Stop Order Generation** (`stop`) after the demonstration to stop new order intake.

Expected scenario effects should appear within tens of seconds.

## 9. Extended failure demo

1. Run **Fail Next 10 Orders** (`failure`). Retain the returned scenario ID only if you
   want to investigate those orders directly.
2. Show events moving through both retry topics and the dead-letter topic.
3. Confirm that unresolved dead letters reaches exactly ten.
4. Run **Redrive All Dead Letters** (`redrive`).
5. Confirm unresolved dead letters returns to zero and downstream processing
   completes.

## 10. Teardown

Destroy Datadog resources first:

```bash
scripts/datadog-terraform destroy-plan
CONFIRM_DATADOG_DESTROY=fx-demo scripts/datadog-terraform destroy-apply
```

Then review and destroy AWS resources:

```bash
cd infra/aws-cdk
npx cdk diff
npx cdk destroy
```

Finally, verify in the AWS and Datadog consoles that resources, volumes, load
balancers, log retention, and billable telemetry no longer remain.
