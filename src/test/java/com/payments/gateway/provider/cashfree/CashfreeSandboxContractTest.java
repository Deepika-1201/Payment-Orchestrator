package com.payments.gateway.provider.cashfree;

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
 * The assumptions the stub cannot prove, checked against Cashfree's sandbox (ADR-031, ADR-032): link creation is
 * idempotent by {@code link_id} (409 recognized), lookup by our attempt id, key rejection, and the settlement
 * reconciliation request. Runs only with sandbox keys:
 * {@code CASHFREE_CLIENT_ID=... CASHFREE_CLIENT_SECRET=... ./gradlew test --tests '*CashfreeSandbox*'}.
 */
@EnabledIfEnvironmentVariable(named = "CASHFREE_CLIENT_ID", matches = ".+")
class CashfreeSandboxContractTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    private CashfreePaymentProvider provider() {
        return new CashfreePaymentProvider(new CashfreeProperties(true, CashfreeProperties.SANDBOX, "2025-01-01", false,
                Duration.ofMinutes(15), Duration.ofDays(5)), new CashfreeApi(CashfreeProperties.SANDBOX, Duration.ofSeconds(5),
                Duration.ofSeconds(20), "2025-01-01", json), Clock.systemUTC());
    }

    private static MerchantAccount account(String secret) {
        return new MerchantAccount("mpa_sandbox", "mer_sandbox", CashfreeApi.CODE,
                Map.of("client_id", System.getenv("CASHFREE_CLIENT_ID"), "client_secret", secret));
    }

    @Test
    void linkCreationIsIdempotentByAttemptIdAndFindableAfterATimeout() {
        MerchantAccount account = account(System.getenv("CASHFREE_CLIENT_SECRET"));
        String attemptId = "att_sandbox_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        InitiatePaymentRequest request = new InitiatePaymentRequest(attemptId, "mer_sandbox", Money.of(100, "INR"),
                PaymentMethod.card(), CaptureMethod.AUTOMATIC, "payment-gateway sandbox contract test", null,
                "9000090000", "https://example.com/return", null);

        var created = provider().initiatePayment(account, request);
        assertThat(created.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(created.providerReference()).startsWith(CashfreePaymentProvider.LINK_REFERENCE_PREFIX);
        assertThat(created.nextAction().type()).isEqualTo(NextAction.Type.REDIRECT);

        assertThat(provider().initiatePayment(account, request).providerReference())
                .as("Cashfree's duplicate link_id answer is recognized").isEqualTo(created.providerReference());
        var byAttempt = provider().fetchPaymentStatus(account, new PaymentStatusQuery(attemptId, null));
        assertThat(byAttempt.providerReference()).isEqualTo(created.providerReference());
        assertThat(provider().fetchPaymentStatus(account, new PaymentStatusQuery("att_sandbox_never_created_0000", null))
                .outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void aWrongSecretIsACredentialsFailureNotADecline() {
        assertThatThrownBy(() -> provider().fetchPaymentStatus(account("not-the-secret"),
                new PaymentStatusQuery("att_sandbox_x", "att_sandbox_x")))
                .isInstanceOf(ProviderCredentialsException.class);
    }

    @Test
    void aWeeksSettlementReconciliationIsAcceptedAndReadable() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.DAYS);
        SettlementReport report = provider().fetchSettlementReport(account(System.getenv("CASHFREE_CLIENT_SECRET")),
                new SettlementReportQuery("mer_sandbox", to.minus(Duration.ofDays(7)), to));

        assertThat(report.lines()).allSatisfy(line -> assertThat(line.settlementId()).isNotNull());
        assertThat(report.settlements()).extracting(SettlementReport.Settlement::settlementId).doesNotHaveDuplicates();
    }
}
