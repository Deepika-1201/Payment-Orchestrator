package com.payments.gateway.shared.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlSafetyValidatorTest {

    private final UrlSafetyValidator strict = new UrlSafetyValidator(true, false);

    @Test
    void rejectsInsecureOrInternalTargets() {
        assertThat(strict.check("http://example.com/hook")).contains("must use https");
        assertThat(strict.check("https://127.0.0.1/hook")).contains("must not resolve to a private or internal address");
        assertThat(strict.check("https://169.254.169.254/latest/meta-data")).isPresent();
        assertThat(strict.check("https://10.1.2.3/hook")).isPresent();
        assertThat(strict.check("https://user:pass@example.com/hook")).isPresent();
        assertThat(strict.check("ftp://example.com")).isPresent();
    }

    @Test
    void classifiesAddressRanges() throws UnknownHostException {
        assertThat(UrlSafetyValidator.isInternal(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(UrlSafetyValidator.isInternal(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(UrlSafetyValidator.isInternal(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(UrlSafetyValidator.isInternal(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void localModeAllowsHttpAndPrivateTargets() {
        UrlSafetyValidator local = new UrlSafetyValidator(false, true);

        assertThat(local.check("http://127.0.0.1:9000/hook")).isEmpty();
    }
}
