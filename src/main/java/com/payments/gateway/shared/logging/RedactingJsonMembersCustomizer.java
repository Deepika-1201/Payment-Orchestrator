package com.payments.gateway.shared.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Applies {@link LogRedactor} to every string in structured (ECS) log lines: message, MDC values and stack traces. */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedactor::redact));
    }
}
