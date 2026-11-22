package io.github.komokendd.dsm.brokerage.common;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

public final class AvroSpecificSerializer implements Serializer<SpecificRecord> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // The schema travels with the generated record type; no registry is used.
    }

    @Override
    public byte[] serialize(String topic, SpecificRecord value) {
        if (value == null) {
            return null;
        }
        try (var output = new ByteArrayOutputStream()) {
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

