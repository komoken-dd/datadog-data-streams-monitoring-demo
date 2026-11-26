package io.github.komokendd.dsm.brokerage.common;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

public final class AvroSpecificSerializer implements Serializer<SpecificRecord> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // Writer schemas are bundled with consumers; the wire carries their fingerprint.
    }

    @Override
    public byte[] serialize(String topic, SpecificRecord value) {
        if (value == null) {
            return null;
        }
        try (var output = new ByteArrayOutputStream()) {
            // Avro single-object encoding: marker followed by CRC-64-AVRO in
            // little-endian order. The payload remains ordinary Avro binary.
            output.write(ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0xc3).put((byte) 0x01)
                    .putLong(SchemaNormalization.parsingFingerprint64(value.getSchema())).array());
            var writer = new SpecificDatumWriter<SpecificRecord>(value.getSchema());
            BinaryEncoder encoder = EncoderFactory.get().directBinaryEncoder(output, null);
            writer.write(value, encoder);
            encoder.flush();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new SerializationException("Unable to serialize Avro record for " + topic, exception);
        }
    }
}

