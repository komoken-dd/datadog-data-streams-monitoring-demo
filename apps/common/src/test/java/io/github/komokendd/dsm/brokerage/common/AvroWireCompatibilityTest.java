package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.komokendd.dsm.brokerage.schema.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificData;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

class AvroWireCompatibilityTest {
    private static final List<Class<? extends SpecificRecord>> CONTRACTS = List.of(
            OrderAcceptedEvent.class, ExecutionReportEvent.class, OrderExpiredEvent.class,
            BuyingPowerUpdatedEvent.class, TradeReportIssuedEvent.class,
            NotificationRequest.class, NotificationDeliveredEvent.class);

    @Test
    void everyContractReadsRetainedRawV1AndNewSingleObjectRecords() throws Exception {
        for (Class<? extends SpecificRecord> type : CONTRACTS) {
            Schema old = AvroSchemaCatalog.legacy(type.getSimpleName());
            byte[] raw = raw(sample(old));
            SpecificData model = SpecificData.getForClass(type);
            SpecificRecord expected = new SpecificDatumReader<SpecificRecord>(old, model.getSchema(type), model)
                    .read(null, DecoderFactory.get().binaryDecoder(raw, null));
            var consumer = new AvroSpecificDeserializer<>(type);
            assertThat(consumer.deserialize("retained-v1", raw)).isEqualTo(expected);
            byte[] framed = new AvroSpecificSerializer().serialize("current", expected);
            assertThat(framed[0]).isEqualTo((byte) 0xc3);
            assertThat(framed[1]).isEqualTo((byte) 0x01);
            assertThat(ByteBuffer.wrap(framed, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong())
                    .isEqualTo(SchemaNormalization.parsingFingerprint64(expected.getSchema()));
            assertThat(consumer.deserialize("current", framed)).isEqualTo(expected);
        }
    }

    @Test
    void actualReaderUsesHistoricalWriterLayoutAndResolvesDefaultedAddedField() throws Exception {
        Schema current = OrderAcceptedEvent.getClassSchema();
        Schema old = Schema.createRecord(current.getName(), current.getDoc(), current.getNamespace(), false);
        old.setFields(current.getFields().stream().filter(f -> !f.name().equals("demoScenarioId"))
                .map(f -> new Schema.Field(f.name(), f.schema(), f.doc(), f.hasDefaultValue() ? f.defaultVal() : null))
                .toList());
        long fingerprint = SchemaNormalization.parsingFingerprint64(old);
        var consumer = new AvroSpecificDeserializer<>(OrderAcceptedEvent.class, Map.of(fingerprint, old));
        var output = new ByteArrayOutputStream();
        output.write(ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xc3).put((byte) 0x01).putLong(fingerprint).array());
        output.write(raw(sample(old)));
        OrderAcceptedEvent event = consumer.deserialize("evolved", output.toByteArray());
        assertThat(event.getDemoScenarioId()).isNull();
        assertThat(event.getOrderId()).isEqualTo("orderId");
        assertThat(event.getQuantity()).isEqualTo(100);
        // This is a writer catalog fixture, not an invented historical production
        // schema: production snapshots remain the exact published v1 contracts.
    }

    @Test
    void unknownSchemasMalformedFramesAndTrailingDataFailVisibly() throws Exception {
        var consumer = new AvroSpecificDeserializer<>(OrderAcceptedEvent.class);
        assertThatThrownBy(() -> consumer.deserialize("bad", new byte[] {(byte) 0xc3, 1}))
                .isInstanceOf(SerializationException.class);
        byte[] unknown = ByteBuffer.allocate(10).put((byte) 0xc3).put((byte) 1).putLong(0L).array();
        assertThatThrownBy(() -> consumer.deserialize("unknown-writer", unknown))
                .isInstanceOf(SerializationException.class).hasRootCauseMessage(
                        "Unknown Avro writer schema fingerprint for " + OrderAcceptedEvent.getClassSchema().getFullName());
        byte[] valid = raw(sample(AvroSchemaCatalog.legacy("OrderAcceptedEvent")));
        assertThatThrownBy(() -> consumer.deserialize("trailing", Arrays.copyOf(valid, valid.length + 1)))
                .isInstanceOf(SerializationException.class);
        assertThat(consumer.deserialize("tombstone", null)).isNull();
        assertThat(new AvroSpecificSerializer().serialize("tombstone", null)).isNull();
    }

    private static GenericRecord sample(Schema schema) {
        var record = new GenericData.Record(schema);
        for (Schema.Field field : schema.getFields()) {
            Object value = switch (field.schema().getType()) {
                case STRING -> field.name();
                case INT -> 1;
                case LONG -> 100L;
                case BOOLEAN -> false;
                case BYTES -> ByteBuffer.wrap(new byte[] {1});
                case UNION -> null;
                default -> throw new IllegalArgumentException("Unhandled sample field " + field.name());
            };
            record.put(field.name(), value);
        }
        return record;
    }

    private static byte[] raw(GenericRecord value) throws Exception {
        var output = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(output, null);
        new GenericDatumWriter<GenericRecord>(value.getSchema()).write(value, encoder);
        encoder.flush();
        return output.toByteArray();
    }
}
