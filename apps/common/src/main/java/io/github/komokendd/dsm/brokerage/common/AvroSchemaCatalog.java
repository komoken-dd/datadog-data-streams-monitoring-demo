package io.github.komokendd.dsm.brokerage.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;

/** Immutable historical writer schemas shipped with consumers, independent of the current reader. */
final class AvroSchemaCatalog {
    private static final Map<Long, Schema> WRITERS = loadWriters();

    private AvroSchemaCatalog() {
    }

    static Schema legacy(String simpleName) throws IOException {
        return load("avro/v1/" + simpleName + ".avsc");
    }

    static Map<Long, Schema> writers() {
        return WRITERS;
    }

    static Schema writer(long fingerprint, Schema current, Map<Long, Schema> historical) {
        Schema writer = SchemaNormalization.parsingFingerprint64(current) == fingerprint
                ? current : historical.get(fingerprint);
        if (writer == null || !writer.getFullName().equals(current.getFullName())) {
            throw new IllegalArgumentException("Unknown Avro writer schema fingerprint for " + current.getFullName());
        }
        return writer;
    }

    private static Map<Long, Schema> loadWriters() {
        try (var input = AvroSchemaCatalog.class.getClassLoader().getResourceAsStream("avro/writer-schemas.txt")) {
            if (input == null) {
                throw new IOException("Missing Avro writer schema catalog");
            }
            var schemas = new HashMap<Long, Schema>();
            for (String resource : new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().toList()) {
                if (!resource.isBlank() && !resource.startsWith("#")) {
                    Schema schema = load(resource);
                    schemas.put(SchemaNormalization.parsingFingerprint64(schema), schema);
                }
            }
            return Map.copyOf(schemas);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Schema load(String resource) throws IOException {
        try (var input = AvroSchemaCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing immutable Avro writer schema: " + resource);
            }
            return new Schema.Parser().parse(input);
        }
    }
}
