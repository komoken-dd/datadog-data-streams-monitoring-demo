#!/usr/bin/env bash
set -Eeuo pipefail

# Isolated test-only KRaft broker. Start inside a disposable container/private pod;
# never use this unauthenticated single-node configuration for application deployment.
export LOG_DIR="${LOG_DIR:-/tmp/kafka-test-logs}"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms256m -Xmx384m}"
mkdir -p "${LOG_DIR}"
cat > /tmp/test-kafka.properties <<'CONFIG'
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:19093
listeners=PLAINTEXT://:19092,CONTROLLER://:19093
advertised.listeners=PLAINTEXT://127.0.0.1:19092
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
log.dirs=/tmp/kraft-test-data
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=false
CONFIG
cluster_id="$(/opt/kafka/bin/kafka-storage.sh random-uuid)"
/opt/kafka/bin/kafka-storage.sh format --standalone --cluster-id "${cluster_id}" --config /tmp/test-kafka.properties
exec /opt/kafka/bin/kafka-server-start.sh /tmp/test-kafka.properties
