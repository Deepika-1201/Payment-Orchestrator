package com.payments.gateway.shared.json;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Thin wrapper over the application's Jackson mapper (snake_case, ISO-8601 dates). */
@Component
public class JsonCodec {

    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public <T> T read(String json, TypeReference<T> type) {
        return mapper.readValue(json, type);
    }
}
