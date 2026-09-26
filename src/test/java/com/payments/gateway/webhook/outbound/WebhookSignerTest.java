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
}
