# Java build and dependency maintenance

The application compiles Java 21 bytecode. Both the Maven builder and final
application runtime use Temurin 21.0.13.1+2. The version is checked from the
archive's `release` file during image creation, and CI executes `java -version`
inside the final runtime image.

## Container inputs

`config/container-images.sh` pins the OS/tooling images by digest and pins the
official Temurin Linux JDK/JRE archives for AMD64 and ARM64 by SHA-256. The
Containerfile replaces the builder JDK before running Java. It constructs the
target UBI 9 minimal runtime filesystem as data, installs only the verified JRE, and copies
only that filesystem into the final image; cross-architecture builds require
no emulation to execute target code.

This archive step is intentional: an upstream security respin can publish
AMD64 container images before ARM64 images. A tag that exists is not evidence
that both platforms are available. Review the official archive checksums,
Containerfile defaults, image lock, and actual JVM together on updates. Once
upstream patched multi-architecture images are verified, the archive stages
can be simplified without changing the required JVM version.

The base-image tag describes its OS/tooling input, not the final patched Java
installation. Continue scanning OS packages separately. Existing running
workloads are updated only after rebuilding and deploying the application
images; changing this repository alone does not patch a cluster.

## Dependency gate

CI creates an aggregate CycloneDX SBOM from resolved Maven dependencies,
excluding test/provided scopes, and submits exact Maven package/version pairs
to OSV. `scripts/check-runtime-dependencies apps/target/bom.json` fails on
untriaged advisory matches or an incomplete/unavailable response. It does not
claim exploitability from a version match, nor does a clean result prove that
all upstream advisories or OS packages are covered.

`config/security-exceptions.json` permits only exact package/version/advisory
exceptions, with a reason, owner, and expiry. Changes to those prerequisites
require re-review. Expired exceptions fail CI. Add no wildcard, package-wide,
or permanent suppression. CI also scans the representative final runtime OS
with Trivy and rejects fixable high/critical findings; unfixed findings remain
in the saved report for review. All application runtime Java coordinates are
covered by the aggregate SBOM.

## Spring baseline

Spring Boot 4.1.3 and its Spring Framework 7.0 maintenance line replace the
unsupported Boot 3.5 / Framework 6.2 baseline. The application uses Boot's
modular Web MVC, Kafka, Flyway, health and RestClient components. REST uses
Jackson 3; Avro's Jackson 2 dependency remains separately aligned. Patched
Tomcat 11, Jackson 2, OpenTelemetry, JDBC and compression dependencies are
explicitly aligned in the parent POM. No Spring vulnerability exceptions remain.

Before rollout, verify JSON contracts, startup/migrations, failure recovery,
Datadog instrumentation and DSM pathways in both Kubernetes targets. CI covers
Java tests (including isolated PostgreSQL and Kafka offset integration tests), dependency and OS
scans, and infrastructure/render checks. A passing build does not substitute
for post-deployment observability verification. The opt-in
[local pipeline test](local-pipeline-test.md) covers all ten services with real
Kafka, schema-scoped PostgreSQL credentials and the notification recovery path.

## Tracer compatibility review (2026-11-25)

The pinned Java tracer is 1.69.1. Its release includes a Spring MVC 7
`SmartHttpMessageConverter` instrumentation fix. The tagged source contains a
Spring 7 / Jackson 3 / Servlet 6.1 test suite and declares Kafka client
compatibility across `[3.8.0,5)`, which includes this application's 4.2.2 client.
These are implementation compatibility signals. The public support matrix still
lists Spring Boot through 3.x, alongside Spring MVC 4.0+ and Tomcat 5.5+; it does
not yet explicitly promise Boot 4 support.

Sources: https://github.com/DataDog/dd-trace-java/releases/tag/v1.69.1
https://github.com/DataDog/dd-trace-java/blob/v1.69.1/dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/build.gradle
https://github.com/DataDog/dd-trace-java/blob/v1.69.1/dd-java-agent/instrumentation/kafka/kafka-clients-3.8/build.gradle
https://docs.datadoghq.com/tracing/trace_collection/compatibility/java/

An isolated macOS ARM64 / Java 21 smoke test used the official 1.69.1 Maven JAR
with its published SHA-256 checksum. Boot 4.1.3 / Tomcat 11.0.29 started, public
health and authenticated Spring MVC / Jackson 3 JSON requests returned 200,
and the tracer transformed Spring MVC and Tomcat classes with no linkage or
class-not-found errors. Export targeted a closed loopback port; telemetry,
remote configuration, profiling and AppSec were disabled. This verifies local
APM linkage, not Datadog ingestion, Linux admission injection, WAF or DSM output.

Kubernetes installs the pinned tracer through the admission controller, using
the application's injection label and custom image annotations. It is
intentionally absent from the application JAR and base runtime. Before rollout
acceptance, verify the injected init containers/agent options, APM traces,
Kafka throughput/lag and intended DSM edges. The functional pipeline harness
disables exports and cannot prove those observations.

Sources: https://docs.datadoghq.com/tracing/guide/local_sdk_injection/
https://docs.datadoghq.com/data_streams/setup/language/java/

Do not infer automatic schema UI visibility merely from using Avro. In the
pinned tracer's source, schema-registry instrumentation names Confluent's
serializer/deserializer classes; this demo uses custom Avro classes and
single-object encoding. A remaining `SpecificDatumReader` call alone does not
prove automatic topic/schema sampling. Persisting bytes in the outbox also
moves serialization outside the Kafka producer call. Schema visibility needs
separate verification; no internal tracer API or synthetic schema metadata is
used to manufacture it.

Source: https://github.com/DataDog/dd-trace-java/blob/v1.69.1/dd-java-agent/instrumentation/confluent-schema-registry/confluent-schema-registry-4.1/src/main/java/datadog/trace/instrumentation/confluentschemaregistry/KafkaDeserializerInstrumentation.java

CI deliberately builds `apps/order-service` as its representative runtime
context: the Containerfile copies only `target/*.jar`, while the OS/JRE stages
are shared by all services. The complete aggregate SBOM checks all application
Java dependencies; the representative OS scan does not substitute for that.

Official references:

- https://github.com/adoptium/temurin/issues/326
- https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13.1%2B2
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- https://spring.io/projects/spring-boot#support
