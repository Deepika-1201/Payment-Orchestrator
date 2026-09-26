package com.payments.gateway.webhook.outbound;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    @Test
    void signatureRoundTripsAndRejectsTamperingOrReplay() {
        String body = "{\"id\":\"evt_1\"}";
        String header = WebhookSigner.sign("whsec_secret", 1_000, body);

        assertThat(WebhookSigner.verify(header, "whsec_secret", body, 1_100, 300)).isTrue();
        assertThat(WebhookSigner.verify(header, "whsec_secret", body + " ", 1_100, 300)).isFalse();
        assertThat(WebhookSigner.verify(header, "whsec_other", body, 1_100, 300)).isFalse();
        assertThat(WebhookSigner.verify(header, "whsec_secret", body, 2_000, 300)).isFalse();
        assertThat(WebhookSigner.verify("garbage", "whsec_secret", body, 1_000, 300)).isFalse();
    }

    @Test
    void duringRotationEitherSecretVerifies() {
        String body = "{\"id\":\"evt_2\"}";
        String header = WebhookSigner.sign(java.util.List.of("whsec_new", "whsec_old"), 1_000, body);

        assertThat(header).matches("t=1000,v1=[0-9a-f]{64},v1=[0-9a-f]{64}");
        assertThat(WebhookSigner.verify(header, "whsec_new", body, 1_000, 300)).isTrue();
        assertThat(WebhookSigner.verify(header, "whsec_old", body, 1_000, 300)).isTrue();
        assertThat(WebhookSigner.verify(header, "whsec_other", body, 1_000, 300)).isFalse();
    }
}
