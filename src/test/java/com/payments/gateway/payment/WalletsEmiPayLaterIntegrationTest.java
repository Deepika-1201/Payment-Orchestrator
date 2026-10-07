package com.payments.gateway.payment;

import com.payments.gateway.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Wallets, card EMI, cardless EMI and pay later (FR-PM5, ADR-037, LLD §20). */
class WalletsEmiPayLaterIntegrationTest extends IntegrationTest {

    @Test
    void walletPaymentNamesItsProviderAndSucceeds() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        Map<String, Object> request = request(withProvider("wallet", "phonepe"));

        Response confirmed = assertContract("POST", "/v1/payments/{payment_id}/confirm", request,
                post(merchant, "/v1/payments/" + paymentId + "/confirm", UUID.randomUUID().toString(), request));

        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("redirect");
        assertThat(str(confirmed.body(), "latest_attempt.method")).isEqualTo("wallet");
        assertThat(str(confirmed.body(), "latest_attempt.method_provider")).isEqualTo("phonepe");
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        Response payment = assertContract("GET", "/v1/payments/{payment_id}", null,
                get(merchant, "/v1/payments/" + paymentId));
        assertThat(str(payment.body(), "status")).isEqualTo("succeeded");
        assertThat(str(payment.body(), "latest_attempt.method_provider")).isEqualTo("phonepe");
        assertThat(payment.body().get("latest_attempt")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).doesNotContainKey("emi_plan");
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE method_type = 'WALLET'"
                + " AND method_details ->> 'provider' = 'phonepe'")).isEqualTo(1);
    }

    @Test
    void cardlessEmiAndPayLaterSucceedOnTheMockPsp() {
        TestMerchant merchant = createMerchant(ALPHA);
        for (Map<String, Object> method : java.util.List.of(withProvider("cardless_emi", "zestmoney"),
                withProvider("pay_later", "lazypay"))) {
            String paymentId = str(createPayment(merchant, 500_000, "automatic"), "id");
            Response confirmed = confirm(merchant, paymentId, method);
            assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
            assertThat(str(confirmed.body(), "latest_attempt.method")).isEqualTo(method.get("type"));
            simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
            assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        }
    }

    @Test
    void cardEmiRecordsThePlanTheCustomerChoseAtThePsp() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 300_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, Map.of("type", "emi"));
        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        String reference = str(confirmed.body(), "latest_attempt.provider_reference");

        Response completed = send("POST", "/simulator/" + ALPHA + "/payments/" + reference + "/complete", Map.of(),
                Map.of("outcome", "success", "emi_tenure_months", 9));

        assertThat(completed.status()).as(completed.raw()).isEqualTo(200);
        Response payment = assertContract("GET", "/v1/payments/{payment_id}", null,
                get(merchant, "/v1/payments/" + paymentId));
        assertThat(str(payment.body(), "status")).isEqualTo("succeeded");
        assertThat(str(payment.body(), "latest_attempt.method")).isEqualTo("emi");
        assertThat(str(payment.body(), "latest_attempt.card.last4")).isNotNull();
        assertThat(num(payment.body(), "latest_attempt.emi_plan.tenure_months")).isEqualTo(9);
        assertThat(num(payment.body(), "latest_attempt.emi_plan.interest_rate_bps")).isEqualTo(1500);
        assertThat(str(payment.body(), "latest_attempt.emi_plan.issuer")).isEqualTo("HDFC");
        assertThat(count("SELECT emi_tenure_months FROM payment_attempts WHERE provider_reference = ?", reference)).isEqualTo(9);
    }

    @Test
    void simulatorRefusesATenureThePspDoesNotOfferOrANonEmiPayment() {
        TestMerchant merchant = createMerchant(ALPHA);
        String emiPayment = str(createPayment(merchant, 300_000, "automatic"), "id");
        String emiReference = str(confirm(merchant, emiPayment, Map.of("type", "emi")).body(), "latest_attempt.provider_reference");
        String cardPayment = str(createPayment(merchant, 300_000, "automatic"), "id");
        String cardReference = str(confirm(merchant, cardPayment, card()).body(), "latest_attempt.provider_reference");

        Response odd = send("POST", "/simulator/" + ALPHA + "/payments/" + emiReference + "/complete", Map.of(),
                Map.of("outcome", "success", "emi_tenure_months", 7));
        Response onCard = send("POST", "/simulator/" + ALPHA + "/payments/" + cardReference + "/complete", Map.of(),
                Map.of("outcome", "success", "emi_tenure_months", 6));

        assertThat(odd.status()).isEqualTo(400);
        assertThat(onCard.status()).isEqualTo(400);
        assertThat(str(getPayment(merchant, emiPayment), "status")).isEqualTo("requires_action");
        assertThat(str(getPayment(merchant, cardPayment), "status")).isEqualTo("requires_action");
    }

    @Test
    void cardEmiWithoutAChosenTenureGetsTheShortestPlan() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 300_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, Map.of("type", "emi"));

        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        assertThat(num(getPayment(merchant, paymentId), "latest_attempt.emi_plan.tenure_months")).isEqualTo(3);
    }

    @Test
    void routingPicksThePspThatOffersTheProvider() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        String paytm = str(createPayment(merchant, 49_900, "automatic"), "id");
        String mobikwik = str(createPayment(merchant, 49_900, "automatic"), "id");

        assertThat(str(confirm(merchant, paytm, withProvider("wallet", "paytm")).body(), "latest_attempt.provider"))
                .isEqualTo(BETA);
        assertThat(str(confirm(merchant, mobikwik, withProvider("wallet", "mobikwik")).body(), "latest_attempt.provider"))
                .isEqualTo(ALPHA);
    }

    @Test
    void unknownProvidersAndAmountsOutsideTheRangeAreUnsupported() {
        TestMerchant merchant = createMerchant(ALPHA);
        String unknown = str(createPayment(merchant, 49_900, "automatic"), "id");
        String smallEmi = str(createPayment(merchant, 299_999, "automatic"), "id");
        String betaOnly = str(createPayment(merchant, 49_900, "automatic"), "id");

        Response unknownWallet = confirm(merchant, unknown, withProvider("wallet", "freecharge"));
        Response belowEmiMinimum = confirm(merchant, smallEmi, Map.of("type", "emi"));
        Response paytmOnAlpha = confirm(merchant, betaOnly, withProvider("wallet", "paytm"));

        for (Response refused : new Response[] {unknownWallet, belowEmiMinimum, paytmOnAlpha}) {
            assertThat(refused.status()).as(refused.raw()).isEqualTo(422);
            assertThat(str(refused.body(), "code")).isEqualTo("unsupported_payment_method");
        }
        assertThat(count("SELECT count(*) FROM payment_attempts")).isZero();
    }

    @Test
    void providerIsRequiredAndValidated() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        Map<String, Object> missing = request(Map.of("type", "pay_later"));
        Map<String, Object> malformed = request(withProvider("cardless_emi", "Zest Money"));
        Map<String, Object> wrongObject = request(Map.of("type", "wallet", "pay_later", Map.of("provider", "lazypay")));

        for (Map<String, Object> request : java.util.List.of(missing, malformed, wrongObject)) {
            Response refused = post(merchant, "/v1/payments/" + paymentId + "/confirm", UUID.randomUUID().toString(), request);
            assertContract("POST", "/v1/payments/{payment_id}/confirm", null, refused);
            assertThat(refused.status()).as(refused.raw()).isEqualTo(400);
            assertThat(str(refused.body(), "code")).isEqualTo("validation_error");
        }
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_payment_method");
    }

    @Test
    void databaseKeepsThePlanOnCardAttemptsWithinRange() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 300_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, Map.of("type", "emi"));
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        String emiAttempt = str(confirmed.body(), "latest_attempt.id");
        String upiPayment = str(payAndSucceed(merchant, 10_000), "id");
        String upiAttempt = str(getPayment(merchant, upiPayment), "latest_attempt.id");

        assertThat(violates("UPDATE payment_attempts SET emi_tenure_months = 6 WHERE id = ?", upiAttempt))
                .as("a plan needs a card").isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_tenure_months = NULL WHERE id = ?", emiAttempt))
                .as("no rate or issuer without a tenure").isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_tenure_months = 0 WHERE id = ?", emiAttempt)).isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_tenure_months = 121 WHERE id = ?", emiAttempt)).isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_interest_rate_bps = -1 WHERE id = ?", emiAttempt)).isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_interest_rate_bps = 10001 WHERE id = ?", emiAttempt)).isTrue();
        assertThat(violates("UPDATE payment_attempts SET method_type = 'BITCOIN' WHERE id = ?", emiAttempt)).isTrue();
        assertThat(violates("UPDATE payment_attempts SET emi_tenure_months = 120, emi_interest_rate_bps = NULL WHERE id = ?",
                emiAttempt)).as("the rate is optional").isFalse();
    }

    private boolean violates(String sql, String attemptId) {
        Throwable thrown = catchThrowable(() -> jdbc.sql(sql).param(1, attemptId).update());
        return thrown instanceof DataIntegrityViolationException;
    }

    private static Map<String, Object> withProvider(String type, String provider) {
        return Map.of("type", type, type, Map.of("provider", provider));
    }

    private static Map<String, Object> request(Map<String, Object> method) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("payment_method", method);
        request.put("return_url", "https://merchant.example/return");
        return request;
    }
}
