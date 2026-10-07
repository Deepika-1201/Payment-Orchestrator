package com.payments.gateway.shared;

import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.EmiPlan;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedKernelTest {

    @Test
    void moneyArithmeticIsExactAndCurrencySafe() {
        Money a = Money.of(49_900, "INR");

        assertThat(a.plus(Money.of(100, "INR"))).isEqualTo(Money.of(50_000, "INR"));
        assertThat(a.toDecimalString()).isEqualTo("499.00");
        assertThatThrownBy(() -> a.plus(Money.of(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.minus(Money.of(50_000, "INR"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "inr")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "XYZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "INR").plus(Money.of(1, "INR"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void idsArePrefixedUniqueAndTimeOrdered() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId("pay"));
        }
        assertThat(ids).hasSize(10_000).allMatch(id -> id.matches("pay_[0-9A-HJKMNP-TV-Z]{26}"));
        assertThat(Ids.ulid(1_000L).substring(0, 10)).isLessThan(Ids.ulid(2_000L).substring(0, 10));
    }

    @Test
    void walletsCardlessEmiAndPayLaterNameAProviderAndOnlyThey() {
        assertThat(PaymentMethod.wallet("phonepe").provider()).isEqualTo("phonepe");
        assertThat(PaymentMethod.cardlessEmi("walnut369").type()).isEqualTo(MethodType.CARDLESS_EMI);
        assertThat(PaymentMethod.payLater("ab").provider()).isEqualTo("ab");
        assertThat(PaymentMethod.wallet("a".repeat(32)).provider()).hasSize(32);
        assertThat(PaymentMethod.emi().provider()).isNull();
        for (String bad : new String[] {null, "a", "a".repeat(33), "PhonePe", "phone-pe"}) {
            assertThatThrownBy(() -> PaymentMethod.wallet(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("wallet.provider is required: 2-32 lower-case letters, digits or _");
        }
        assertThatThrownBy(() -> PaymentMethod.payLater(null)).hasMessageStartingWith("pay_later.provider");
        assertThatThrownBy(() -> PaymentMethod.cardlessEmi(null)).hasMessageStartingWith("cardless_emi.provider");
        assertThat(new PaymentMethod(MethodType.EMI, null, null, null, null, "hdfc").provider()).isNull();
        assertThat(new PaymentMethod(MethodType.CARD, null, null, null, null, "hdfc").provider()).isNull();
    }

    @Test
    void emiPlansAreWithinRange() {
        assertThat(new EmiPlan(1, 0, "HD")).isNotNull();
        assertThat(new EmiPlan(120, 10_000, "A".repeat(16))).isNotNull();
        assertThat(new EmiPlan(6, null, null).interestRateBps()).isNull();
        assertThatThrownBy(() -> new EmiPlan(0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(121, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(6, -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(6, 10_001, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(6, null, "hdfc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(6, null, "H")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmiPlan(6, null, "A".repeat(17))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CardDetails("visa", "1111").emiPlan()).isNull();
    }

    @Test
    void ciphertextIsBoundToItsContext() {
        SecretCipher cipher = new SecretCipher(new byte[32]);
        byte[] encrypted = cipher.encrypt("{\"api_key\":\"k\"}", "merchant_provider_account:mpa_1");

        assertThat(cipher.decrypt(encrypted, "merchant_provider_account:mpa_1")).isEqualTo("{\"api_key\":\"k\"}");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "merchant_provider_account:mpa_2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
