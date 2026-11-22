package io.github.komokendd.dsm.brokerage.common;

import java.util.Map;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

public final class AvroSpecificDeserializer<T extends SpecificRecord> implements Deserializer<T> {
    public static final String VALUE_CLASS_CONFIG = "demo.avro.value.class";

    private Class<T> valueClass;

    public AvroSpecificDeserializer() {
    }

    public AvroSpecificDeserializer(Class<T> valueClass) {
        this.valueClass = valueClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs, boolean isKey) {
        if (valueClass != null) {
            return;
        }
        Object configured = configs.get(VALUE_CLASS_CONFIG);
        if (configured == null) {
            throw new ConfigException(VALUE_CLASS_CONFIG + " must name a generated SpecificRecord class");
        }
        try {
            valueClass = (Class<T>) Class.forName(configured.toString());
        } catch (ClassNotFoundException exception) {
            throw new ConfigException("Avro value class not found: " + configured, exception);
        }
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            var reader = new SpecificDatumReader<T>(valueClass);
            var decoder = DecoderFactory.get().binaryDecoder(data, null);
            return reader.read(null, decoder);
        } catch (Exception exception) {
            throw new SerializationException("Unable to deserialize Avro record from " + topic, exception);
        }
    }
}

