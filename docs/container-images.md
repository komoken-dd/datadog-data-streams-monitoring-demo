# Container image policy

This project separates application images built from this repository from
reviewed third-party images. The recommended path for both kind and EKS uses ten
public GHCR application packages built by this repository's release workflow.
An explicit self-built path is available for users who want to inspect or
modify the source and publish their own images. Kubernetes pulls PostgreSQL,
Strimzi, and Datadog directly from their official registries in either case.
The recommended path does not require a private ECR registry and does not copy
third-party images under the user's identity.

`config/container-images.sh` is the reviewed image lock. It records a readable
version tag, the multi-architecture index digest used by Kubernetes, platform
manifest digests needed by the local and CI build paths, and AMD64/ARM64 image
config digests used for deeper runtime review.
Released application images and the reviewed runtime images support Linux
AMD64 and ARM64.

## Image inventory and trust decisions

| Purpose | Upstream | Classification | Decision and security consideration |
|---|---|---|---|
| Maven build environment | `docker.io/library/maven:3.9.16-eclipse-temurin-21` | Docker Official Image | Used only on the developer machine to build jars. It combines the official Maven distribution with Temurin 21. Its large toolchain surface is not deployed to Kubernetes. The host-side Maven Wrapper uses the same Maven release and verifies the distribution SHA-256. |
| Java application runtime | `docker.io/library/eclipse-temurin:21.0.13_9-jre-ubi9-minimal` | Docker Official Image maintained by Eclipse Adoptium | Selected for a current Java 21 JRE, TCK-tested Temurin binaries, glibc compatibility, and a small UBI minimal base. It is not distroless, so OS packages remain part of the vulnerability surface and must be refreshed intentionally. Applications run as UID/GID `10001`, not root. |
| PostgreSQL | `docker.io/library/postgres:17.11-bookworm` | Docker Official Image | Uses the current PostgreSQL 17 minor release rather than a floating `17` tag. The Debian base and database packages remain scan targets. Kubernetes pulls the official image by tag and multi-architecture index digest. |
| Strimzi Operator and Kafka | `quay.io/strimzi/operator:1.1.0` and `quay.io/strimzi/kafka:1.1.0-kafka-4.2.1` | Official images published by the Strimzi project; not Docker Official Images | Generic Kafka images are not substituted because Strimzi's Operator, scripts, Kafka version map, and entity operators are released and tested together. Digest locking prevents a later tag change from changing the deployed bytes; registry availability and upstream supply-chain security remain vendor dependencies. |
| Datadog Operator, Agent, Cluster Agent, Java tracer, and APM injector | `registry.datadoghq.com/*` | Official vendor images published by Datadog; not Docker Official Images | These images implement the Datadog Operator contract, telemetry collection, admission injection, and tracer installation. Replacing them with generic images is not meaningful. Kubernetes uses the vendor registry directly with multi-architecture index digests. The APM injector is pinned to `0.66.0`, the first release containing the fix for CVE-2026-61581. |
| kind node | `docker.io/kindest/node:v1.35.0` | Official image published by the kind project; not a Docker Official Image | This image is the Kubernetes node implementation expected by kind. It runs a privileged local control-plane container and is appropriate only for the local development cluster, never for EKS workloads. |
| EKS system add-ons | Regional AWS-owned ECR repositories | AWS-managed EKS images | VPC CNI, CoreDNS, kube-proxy, EBS CSI, and Pod Identity images remain managed by the EKS add-on service. They use AWS-owned regional ECR repositories rather than the user's ECR, but still use ECR network endpoints and do not require public container registries. |

The Docker Official Image status and maintainers for Maven, Eclipse Temurin,
and PostgreSQL are documented in their Docker Hub pages:

- <https://hub.docker.com/_/maven>
- <https://hub.docker.com/_/eclipse-temurin>
- <https://hub.docker.com/_/postgres>

The project and vendor image sources are documented here:

- <https://strimzi.io/docs/operators/latest/full/deploying.html>
- <https://docs.datadoghq.com/containers/guide/container-images-for-docker-environments/>
- <https://docs.datadoghq.com/containers/kubernetes/installation/>
- <https://kind.sigs.k8s.io/docs/design/node-image/>

The Datadog APM injector security advisory is:

- <https://github.com/DataDog/datadog-agent/security/advisories/GHSA-h8qx-4jh5-pf43>

## Application release provenance

`.github/workflows/release-images.yml` runs only when a GitHub Release is
published. It checks and packages the Java source once, then builds all ten
service images for Linux AMD64 and ARM64. Each image receives two non-floating
references: a semantic release tag and the immutable digest recorded in the
release image lock. The source commit remains explicit in the OCI revision
label and signed provenance instead of a second mutable registry tag.

BuildKit attaches an SBOM and `mode=max` provenance to each GHCR image. GitHub
Artifact Attestations separately bind each multi-architecture image digest to
the repository, release workflow, source commit, and GitHub identity. The
workflow then produces `fx-demo-images-<release>.json`, which contains one
`tag@sha256` reference per service, attests that file, and attaches it to the
GitHub Release.

`scripts/kind-deploy` and `scripts/deploy eks` download the lock and verify its
schema, exact service set, repository prefix, release tag, source revision,
signer workflow, and Artifact Attestation before changing the cluster. The lock
is also a Helm values file, so Kubernetes receives immutable image references
without a second tag-resolution step. Public GHCR packages allow anonymous
cluster pulls; only the local `gh` verification command needs GitHub API
access.

Artifact Attestations prove how and where an artifact was built. They do not
prove that the source or its dependencies are vulnerability-free. Version and
digest review remains part of each release.

- [GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [GitHub Artifact Attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
- [Docker Build attestations](https://docs.docker.com/reference/cli/docker/buildx/build/#attest)

### Optional self-built application images

The self-built path uses the same pinned Maven builder, Temurin runtime base,
Spring Boot layered-jar extraction, and `Containerfile` as the release
workflow. It is an option for source review or local modification, not a more
trusted form of the published release.

For kind, `scripts/build-images` creates local images and
`FX_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy` loads them into the
cluster. For EKS, `TARGET=eks PUSH=true scripts/build-images` builds and pushes
all ten services to the selected `REPOSITORY_PREFIX`. The registry-returned
digests are written to the ignored `deploy/values/values-self-built.json` file,
and `FX_DEMO_IMAGE_SOURCE=self-built scripts/deploy eks` deploys those exact
references.

The build script runs Maven and layered-jar extraction on the Podman machine's
native architecture. It targets the Podman machine architecture for kind and
detects the EKS worker architecture from the selected Kubernetes context. Users
do not select AMD64 or ARM64 manually. A mixed-architecture EKS node group is
outside the self-built path; the recommended GHCR release remains
multi-architecture.

The generated file must contain exactly ten `tag@sha256` references and is
validated before Kubernetes changes. It preserves immutability, but it has no
Artifact Attestation from this repository's release workflow. The builder owns
the registry access, vulnerability review, source-to-image provenance, and any
Kubernetes pull credentials required by a private registry.

### Maintainer release prerequisites

The repository must be public and GitHub Actions must be enabled before a
release is published. GHCR creates each of the ten packages as private on its
first publication. A maintainer must change each package to **Public** once;
subsequent EKS and kind pulls are anonymous. Do not publish a release until the
source tag is final, and do not move a published release tag.

Before publishing a new release, enable Actions and confirm that this workflow
is active. When a release candidate is useful, publish a semantic prerelease
such as `vX.Y.Z-rc.1`, verify all ten public GHCR packages, anonymous pulls,
and Artifact Attestations, and then publish and independently verify the final
`vX.Y.Z` release. GitHub does not replay a release event that occurred while
Actions was disabled.

A rerun may reuse an existing release image only after verifying an Attestation
from this workflow for the same tag and source commit. If a previous run pushed
an image but failed before attesting it, delete only that incomplete GHCR tag
before rerunning. Existing release image manifests are never overwritten: an
identical asset is retained and different content stops the workflow.

No long-lived registry credential is configured. The workflow uses its
short-lived `GITHUB_TOKEN` with job-scoped package, OIDC, and attestation
permissions. All referenced Actions are pinned to full commit SHAs.

## Updating an image

Image updates are deliberate changes:

1. Select a supported upstream release from the official project or vendor.
2. Review its release notes and security advisories.
3. Resolve the multi-architecture index, verify its Linux AMD64 and ARM64
   manifests, and record the platform/config digests used by the tested paths.
4. Update the tag and digests together in `config/container-images.sh`.
5. Run `scripts/verify-container-images`.
6. Rebuild application images when Maven or Temurin changes.
7. Render the Helm manifests and confirm that PostgreSQL, Strimzi, and Datadog
   still use official-registry `tag@digest` references.
8. Update the pinned release examples in `README.md`, `README.ja.md`, and
   `docs/demo-runbook.md` to a release that readers can download.
9. Publish a test release, verify the GHCR attestations and release lock, and
   exercise AMD64 and ARM64 paths for both kind and EKS.

Do not replace a digest while retaining an existing release tag. Publish a new
semantic release so the old and new artifacts remain distinguishable.
