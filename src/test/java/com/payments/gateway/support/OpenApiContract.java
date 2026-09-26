package com.payments.gateway.support;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Checks HTTP exchanges against {@code docs/openapi.yaml}. Object schemas are made strict here
 * ({@code additionalProperties: false}), so an undocumented response field fails the build while the published
 * contract still tells clients to ignore unknown fields.
 */
public final class OpenApiContract {

    private static final String BASE = "https://contract.test/schemas.json";
    private static final String COMPONENT_REF = "#/components/schemas/";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final OpenApiContract INSTANCE = load(Path.of("docs", "openapi.yaml"));

    private final JsonNode spec;
    private final SchemaRegistry registry;
    private final ConcurrentMap<String, Schema> schemas = new ConcurrentHashMap<>();

    private OpenApiContract(JsonNode spec, SchemaRegistry registry) {
        this.spec = spec;
        this.registry = registry;
    }

    public static OpenApiContract get() {
        return INSTANCE;
    }

    /** Documented operations as {@code "METHOD /path"} with path parameter names replaced by {@code {}}. */
    public Set<String> operations() {
        Set<String> operations = new TreeSet<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .map(Map.Entry::getKey)
                .filter(HTTP_METHODS::contains)
                .forEach(method -> operations.add(method.toUpperCase(Locale.ROOT) + " " + normalize(path.getKey()))));
        return operations;
    }

    public static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    /** Validates a request body the test sent against the operation's JSON request schema. */
    public void assertRequest(String method, String path, String body) {
        JsonNode schema = operation(method, path).path("requestBody").path("content").path("application/json").path("schema");
        assertThat(schema.isMissingNode()).as("%s %s documents a JSON request body", method, path).isFalse();
        validate(schema, body, method + " " + path + " request");
    }

    /** Validates status, media type, required headers and body of a response against the operation. */
    public void assertResponse(String method, String path, int status, HttpHeaders headers, String body) {
        JsonNode response = resolve(operation(method, path).path("responses").path(String.valueOf(status)));
        if (response.isMissingNode()) {
            fail("%s %s does not document status %d", method, path, status);
        }
        response.path("headers").properties().forEach(header -> {
            if (resolve(header.getValue()).path("required").asBoolean(false)) {
                assertThat(headers.firstValue(header.getKey()))
                        .as("%s %s %d requires header %s", method, path, status, header.getKey()).isPresent();
            }
        });
        String mediaType = headers.firstValue("Content-Type").map(value -> value.split(";")[0].trim()).orElse("");
        JsonNode schema = response.path("content").path(mediaType).path("schema");
        if (schema.isMissingNode()) {
            fail("%s %s %d does not document media type '%s'", method, path, status, mediaType);
        }
        validate(schema, body, method + " " + path + " " + status);
    }

    /** Validates a document against a named component schema. */
    public void assertSchema(String componentSchema, String body) {
        validate(JsonMapper.shared().createObjectNode().put("$ref", COMPONENT_REF + componentSchema), body,
                componentSchema);
    }

    private JsonNode operation(String method, String path) {
        JsonNode operation = spec.path("paths").path(path).path(method.toLowerCase(Locale.ROOT));
        if (operation.isMissingNode()) {
            fail("%s %s is not documented in docs/openapi.yaml", method, path);
        }
        return operation;
    }

    private void validate(JsonNode schemaNode, String body, String what) {
        String ref = schemaNode.path("$ref").asString("");
        assertThat(ref).as("%s must use a $ref'd component schema", what).startsWith(COMPONENT_REF);
        Schema schema = schemas.computeIfAbsent(ref, key ->
                registry.getSchema(SchemaLocation.of(BASE + "#/$defs/" + key.substring(COMPONENT_REF.length()))));
        List<com.networknt.schema.Error> errors = schema.validate(body, InputFormat.JSON);
        assertThat(errors).as("%s violates the contract; body: %s", what, body).isEmpty();
    }

    private JsonNode resolve(JsonNode node) {
        String ref = node.path("$ref").asString("");
        return ref.isEmpty() ? node : spec.at(ref.substring(1));
    }

    private static OpenApiContract load(Path path) {
        String yaml;
        try {
            yaml = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the API contract " + path.toAbsolutePath(), e);
        }
        JsonNode spec = YAMLMapper.builder().build().readTree(yaml);
        JsonNode schemas = spec.path("components").path("schemas").deepCopy();
        makeStrict(schemas);
        // Only the component schemas, as standard $defs: the validator sees pure JSON Schema 2020-12.
        String document = JsonMapper.shared().writeValueAsString(JsonMapper.shared().createObjectNode().set("$defs", schemas))
                .replace("\"" + COMPONENT_REF, "\"#/$defs/");
        SchemaRegistry registry = SchemaRegistry.withDialect(Dialects.getOpenApi31(),
                builder -> builder.schemas(Map.of(BASE, document)));
        return new OpenApiContract(spec, registry);
    }

    private static void makeStrict(JsonNode node) {
        if (node instanceof ObjectNode object && object.has("properties") && !object.has("additionalProperties")
                && "object".equals(object.path("type").asString(""))) {
            object.put("additionalProperties", false);
        }
        for (JsonNode child : node) {
            makeStrict(child);
        }
    }
}
