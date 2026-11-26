package io.github.komokendd.dsm.brokerage.common;

import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificData;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

public final class AvroSpecificDeserializer<T extends SpecificRecord> implements Deserializer<T> {
    public static final String VALUE_CLASS_CONFIG = "demo.avro.value.class";

    private Class<T> valueClass;
    private final Map<Long, Schema> historicalWriters;

    public AvroSpecificDeserializer() {
        this.historicalWriters = AvroSchemaCatalog.writers();
    }

    public AvroSpecificDeserializer(Class<T> valueClass) {
        this(valueClass, AvroSchemaCatalog.writers());
    }

    AvroSpecificDeserializer(Class<T> valueClass, Map<Long, Schema> historicalWriters) {
        this.valueClass = valueClass;
        this.historicalWriters = Map.copyOf(historicalWriters);
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
            if (valueClass == null) {
                throw new IllegalStateException("Avro value class has not been configured");
            }
            SpecificData specificData = SpecificData.getForClass(valueClass);
            Schema readerSchema = specificData.getSchema(valueClass);
            Schema writerSchema;
            int offset = 0;
            if (data.length >= 2 && data[0] == (byte) 0xc3 && data[1] == (byte) 0x01) {
                if (data.length < 10) {
                    throw new IllegalArgumentException("Truncated Avro single-object header");
                }
                long fingerprint = ByteBuffer.wrap(data, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                writerSchema = AvroSchemaCatalog.writer(fingerprint, readerSchema, historicalWriters);
                offset = 10;
            } else {
                // Raw pre-framing messages have exactly the immutable original v1
                // writer layout. Never guess their writer from the current reader.
                writerSchema = AvroSchemaCatalog.legacy(valueClass.getSimpleName());
            }
            var reader = new SpecificDatumReader<T>(writerSchema, readerSchema, specificData);
            var decoder = DecoderFactory.get().binaryDecoder(data, offset, data.length - offset, null);
            T record = reader.read(null, decoder);
            if (!decoder.isEnd()) {
                throw new IllegalArgumentException("Trailing bytes after Avro record");
            }
            return record;
        } catch (Exception exception) {
            throw new SerializationException("Unable to deserialize Avro record from " + topic, exception);
        }
    }
}

