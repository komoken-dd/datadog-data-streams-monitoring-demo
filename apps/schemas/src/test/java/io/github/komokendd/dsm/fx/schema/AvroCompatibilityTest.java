package io.github.komokendd.dsm.fx.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

class AvroCompatibilityTest {
    // These resources are immutable snapshots of the contracts published as v1.
    private static final String V1_RESOURCE_ROOT = "avro/v1/";

    @Test
    void currentReadersRemainCompatibleWithPublishedV1Contracts() {
        List<Contract> contracts = List.of(
                new Contract("OrderReceivedEvent.avsc", OrderReceivedEvent.getClassSchema()),
                new Contract("OrderApprovedEvent.avsc", OrderApprovedEvent.getClassSchema()),
                new Contract("OrderRejectedEvent.avsc", OrderRejectedEvent.getClassSchema()),
                new Contract("TradeExecutedEvent.avsc", TradeExecutedEvent.getClassSchema()),
                new Contract("TradeConfirmedEvent.avsc", TradeConfirmedEvent.getClassSchema()),
                new Contract("SettlementInstructedEvent.avsc", SettlementInstructedEvent.getClassSchema()));

        for (Contract contract : contracts) {
            Schema publishedV1 = loadPublishedSchema(contract.resourceName());
            var result = SchemaCompatibility.checkReaderWriterCompatibility(
                    contract.currentReader(), publishedV1);

            assertThat(result.getType())
                    .as(
                            "current reader remains compatible with published v1 schema %s",
                            publishedV1.getFullName())
                    .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
            assertThat(result.getResult().getIncompatibilities()).isEmpty();
        }
    }

    private Schema loadPublishedSchema(String resourceName) {
        String resource = V1_RESOURCE_ROOT + resourceName;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("published schema resource %s", resource).isNotNull();
            return new Schema.Parser().parse(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read published schema resource " + resource, exception);
        }
    }

    private record Contract(String resourceName, Schema currentReader) {
    }
}
