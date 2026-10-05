package com.payments.gateway.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderPaymentResult.Outcome;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assumptions the stub cannot prove, checked against Razorpay test mode (ADR-030, ADR-032): idempotent link creation
 * by {@code reference_id}, lookup by our attempt id, key rejection, and the settlement recon request. Runs only with test
 * keys: {@code RAZORPAY_KEY_ID=rzp_test_... RAZORPAY_KEY_SECRET=... ./gradlew test --tests '*RazorpaySandbox*'}.
 * Creates one Payment Link per run (test mode allows 30 per account).
 */
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = "rzp_test_.+")
class RazorpaySandboxContractTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final URI api = URI.create("https://api.razorpay.com/v1");

    private RazorpayPaymentProvider provider() {
        return new RazorpayPaymentProvider(new RazorpayProperties(true, api, false, Duration.ofMinutes(15), Duration.ofDays(5)),
                new RazorpayApi(api, Duration.ofSeconds(5), Duration.ofSeconds(20), "rzp_test_", json), Clock.systemUTC());
    }

    private static MerchantAccount account(String secret) {
        return new MerchantAccount("mpa_sandbox", "mer_sandbox", RazorpayApi.CODE, Map.of(
                "key_id", System.getenv("RAZORPAY_KEY_ID"), "key_secret", secret, "webhook_secret", "unused"));
    }

    @Test
    void linkCreationIsIdempotentByAttemptIdAndFindableAfterATimeout() {
        MerchantAccount account = account(System.getenv("RAZORPAY_KEY_SECRET"));
        String attemptId = "att_sandbox_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        InitiatePaymentRequest request = new InitiatePaymentRequest(attemptId, "mer_sandbox", Money.of(100, "INR"),
                PaymentMethod.card(), CaptureMethod.AUTOMATIC, "payment-gateway sandbox contract test", null, null,
                "https://example.com/return", null);

        var created = provider().initiatePayment(account, request);
        assertThat(created.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(created.providerReference()).startsWith("plink_");
        assertThat(created.nextAction().type()).isEqualTo(NextAction.Type.REDIRECT);

        var retried = provider().initiatePayment(account, request);
        assertThat(retried.providerReference()).as("Razorpay's duplicate reference_id error is recognized")
                .isEqualTo(created.providerReference());

        var byAttempt = provider().fetchPaymentStatus(account, new PaymentStatusQuery(attemptId, null));
        assertThat(byAttempt.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(byAttempt.providerReference()).isEqualTo(created.providerReference());

        assertThat(provider().fetchPaymentStatus(account, new PaymentStatusQuery("att_sandbox_never_created_0000", null))
                .outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void aWrongSecretIsACredentialsFailureNotADecline() {
        assertThatThrownBy(() -> provider().fetchPaymentStatus(account("not-the-secret"),
                new PaymentStatusQuery("att_sandbox_x", "order_doesnotexist0")))
                .isInstanceOf(ProviderCredentialsException.class);
    }

    @Test
    void aWeeksSettlementReconIsAcceptedAndReadable() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.DAYS);
        SettlementReport report = provider().fetchSettlementReport(account(System.getenv("RAZORPAY_KEY_SECRET")),
                new SettlementReportQuery("mer_sandbox", to.minus(Duration.ofDays(7)), to));

        assertThat(report.lines()).allSatisfy(line -> assertThat(line.settlementId()).isNotNull());
        assertThat(report.settlements()).extracting(SettlementReport.Settlement::settlementId).doesNotHaveDuplicates();
    }
}
