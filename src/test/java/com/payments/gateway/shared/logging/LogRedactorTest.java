package com.payments.gateway.shared.logging;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {

    @Test
    void secretsAreMasked() {
        assertThat(LogRedactor.redact("key sk_live_8f7a6b5c4d3e2f1a0b9c rejected")).isEqualTo("key sk_live_*** rejected");
        assertThat(LogRedactor.redact("secret whsec_Zx81kL0pQ2vN")).isEqualTo("secret whsec_***");
        assertThat(LogRedactor.redact("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig")).isEqualTo("Authorization: Bearer ***");
        assertThat(LogRedactor.redact("jdbc:postgresql://app:hunter2@db:5432/pg")).isEqualTo("jdbc:postgresql://***:***@db:5432/pg");
    }

    @Test
    void cardNumbersPassingLuhnKeepOnlyTheLastFour() {
        assertThat(LogRedactor.redact("pan 4111111111111111 seen")).isEqualTo("pan ****1111 seen");
        assertThat(LogRedactor.redact("pan 4111 1111 1111 1111")).isEqualTo("pan ****1111");
        assertThat(LogRedactor.redact("order 4111111111111112")).as("fails Luhn").isEqualTo("order 4111111111111112");
        assertThat(LogRedactor.redact("at 1790000000000 ms")).as("epoch millis").isEqualTo("at 1790000000000 ms");
    }

    @Test
    void emailsAndVpasAreMaskedButIdsAreNot() {
        assertThat(LogRedactor.redact("to buyer@example.com")).isEqualTo("to b***@example.com");
        assertThat(LogRedactor.redact("vpa customer@okbank")).isEqualTo("vpa c***@okbank");
        assertThat(LogRedactor.redact("payment pay_01K5Z9V4J6Q2X8N3M7B1C0D4E5 amount 49900"))
                .isEqualTo("payment pay_01K5Z9V4J6Q2X8N3M7B1C0D4E5 amount 49900");
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredLogLinesAreRedacted() {
        JsonWriter<Map<String, String>> writer = JsonWriter.of(members -> {
            members.add("message", event -> event.get("message"));
            new RedactingJsonMembersCustomizer().customize((JsonWriter.Members<Object>) (JsonWriter.Members<?>) members);
        });

        String line = writer.writeToString(Map.of("message", "retry for buyer@example.com with sk_test_abcdef123456"));

        assertThat(line).isEqualTo("{\"message\":\"retry for b***@example.com with sk_test_***\"}");
    }
}
