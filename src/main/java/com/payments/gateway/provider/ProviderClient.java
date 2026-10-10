package com.payments.gateway.provider;

import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MandateRequests.CreateMandateRequest;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationQuery;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationRequest;
import com.payments.gateway.provider.spi.MandateRequests.ExecuteDebitRequest;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderCredit;
import com.payments.gateway.provider.spi.ProviderMandateResult;
import com.payments.gateway.provider.spi.ProviderNotificationResult;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.CloseCollectionRequest;
import com.payments.gateway.provider.spi.ProviderRequests.AcceptDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.ContestDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.DisputeEvidenceUpload;
import com.payments.gateway.provider.spi.ProviderDisputeResponse;
import com.payments.gateway.provider.spi.ProviderRefusedException;
import com.payments.gateway.provider.spi.ProviderRequests.CreditsQuery;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.shared.web.Mdc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Single entry point for PSP calls: merchant account resolution, circuit breaking, failure classification, metrics. */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final ProviderRegistry registry;
    private final ProviderHealthTracker health;
    private final MeterRegistry meters;
    private final MerchantAccountResolver accounts;
    private final CircuitBreakerRegistry circuitBreakers;

    @Autowired
    public ProviderClient(ProviderRegistry registry, ProviderHealthTracker health, MeterRegistry meters,
                          MerchantAccountResolver accounts) {
        this(registry, health, meters, accounts, Duration.ofSeconds(30));
    }

    ProviderClient(ProviderRegistry registry, ProviderHealthTracker health, MeterRegistry meters,
                   MerchantAccountResolver accounts, Duration openStateWait) {
        this.registry = registry;
        this.health = health;
        this.meters = meters;
        this.accounts = accounts;
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openStateWait)
                // Routing never calls a provider whose circuit is open, so a call could never move it to half-open.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class)
                .build());
    }

    public ProviderPaymentResult initiate(String merchantId, String providerCode, InitiatePaymentRequest request) {
        return call(merchantId, providerCode, "initiate", (provider, account) -> provider.initiatePayment(account, request));
    }

    public ProviderPaymentResult fetchStatus(String merchantId, String providerCode, PaymentStatusQuery query) {
        return call(merchantId, providerCode, "status", (provider, account) -> provider.fetchPaymentStatus(account, query));
    }

    public ProviderPaymentResult capture(String merchantId, String providerCode, CaptureRequest request) {
        return call(merchantId, providerCode, "capture", (provider, account) -> provider.capture(account, request));
    }

    public ProviderPaymentResult voidAuthorization(String merchantId, String providerCode, VoidRequest request) {
        return call(merchantId, providerCode, "void", (provider, account) -> provider.voidAuthorization(account, request));
    }

    public ProviderRefundResult refund(String merchantId, String providerCode, RefundRequest request) {
        return call(merchantId, providerCode, "refund", (provider, account) -> provider.refund(account, request));
    }

    public ProviderRefundResult fetchRefundStatus(String merchantId, String providerCode, RefundStatusQuery query) {
        return call(merchantId, providerCode, "refund_status", (provider, account) -> provider.fetchRefundStatus(account, query));
    }

    public ProviderMandateResult createMandate(String merchantId, String providerCode, CreateMandateRequest request) {
        return call(merchantId, providerCode, "mandate_create", (provider, account) -> provider.createMandate(account, request));
    }

    public ProviderMandateResult fetchMandate(String merchantId, String providerCode, MandateQuery query) {
        return call(merchantId, providerCode, "mandate_status", (provider, account) -> provider.fetchMandate(account, query));
    }

    public ProviderMandateResult revokeMandate(String merchantId, String providerCode, MandateQuery query) {
        return call(merchantId, providerCode, "mandate_revoke", (provider, account) -> provider.revokeMandate(account, query));
    }

    public ProviderNotificationResult notifyDebit(String merchantId, String providerCode, DebitNotificationRequest request) {
        return call(merchantId, providerCode, "debit_notify", (provider, account) -> provider.notifyDebit(account, request));
    }

    public ProviderNotificationResult fetchDebitNotification(String merchantId, String providerCode,
                                                             DebitNotificationQuery query) {
        return call(merchantId, providerCode, "debit_notification_status",
                (provider, account) -> provider.fetchDebitNotification(account, query));
    }

    public ProviderPaymentResult executeDebit(String merchantId, String providerCode, ExecuteDebitRequest request) {
        return call(merchantId, providerCode, "debit_execute", (provider, account) -> provider.executeDebit(account, request));
    }

    public List<ProviderCredit> fetchCredits(String merchantId, String providerCode, CreditsQuery query) {
        return call(merchantId, providerCode, "credits", (provider, account) -> provider.fetchCredits(account, query));
    }

    public void closeCollection(String merchantId, String providerCode, CloseCollectionRequest request) {
        call(merchantId, providerCode, "collection_close", (provider, account) -> {
            provider.closeCollection(account, request);
            return null;
        });
    }

    public String uploadDisputeEvidence(String merchantId, String providerCode, DisputeEvidenceUpload upload) {
        return call(merchantId, providerCode, "dispute_evidence_upload",
                (provider, account) -> provider.uploadDisputeEvidence(account, upload));
    }

    public ProviderDisputeResponse contestDispute(String merchantId, String providerCode, ContestDisputeRequest request) {
        return call(merchantId, providerCode, "dispute_contest", (provider, account) -> provider.contestDispute(account, request));
    }

    public ProviderDisputeResponse acceptDispute(String merchantId, String providerCode, AcceptDisputeRequest request) {
        return call(merchantId, providerCode, "dispute_accept", (provider, account) -> provider.acceptDispute(account, request));
    }

    /**
     * Settlement reports are batch reads that can span many pages: they bypass the circuit breaker and routing latency, so
     * a slow or failing report never steers live payments (ADR-032). Adapter errors surface with their message.
     */
    public SettlementReport fetchSettlementReport(String providerCode, SettlementReportQuery query) {
        PaymentProvider provider = registry.require(providerCode);
        MerchantAccount account = accounts.require(query.merchantId(), providerCode);
        MDC.put(Mdc.PROVIDER, providerCode);
        long start = System.nanoTime();
        try {
            SettlementReport report = provider.fetchSettlementReport(account, query);
            record(providerCode, "settlement_report", "ok", System.nanoTime() - start);
            return report;
        } catch (ProviderCredentialsException e) {
            record(providerCode, "settlement_report", "credentials_rejected", System.nanoTime() - start);
            throw e;
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            record(providerCode, "settlement_report", e instanceof ProviderTimeoutException ? "timeout" : "unavailable",
                    System.nanoTime() - start);
            throw e;
        } catch (RuntimeException e) {
            record(providerCode, "settlement_report", "error", System.nanoTime() - start);
            log.error("Settlement report adapter error for merchant account {}", account.id(), e);
            throw new ProviderUnavailableException(providerCode, "settlement report failed: " + e.getMessage(), e);
        } finally {
            MDC.remove(Mdc.PROVIDER);
        }
    }

    public boolean isAvailable(String providerCode) {
        CircuitBreaker.State state = circuitBreakers.circuitBreaker(providerCode).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    public String circuitState(String providerCode) {
        return circuitBreakers.circuitBreaker(providerCode).getState().name();
    }

    public void resetCircuits() {
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private <T> T call(String merchantId, String providerCode, String operation,
                       BiFunction<PaymentProvider, MerchantAccount, T> action) {
        PaymentProvider provider = registry.require(providerCode);
        MerchantAccount account = accounts.require(merchantId, providerCode);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(providerCode);
        if (!breaker.tryAcquirePermission()) {
            record(providerCode, operation, "circuit_open", 0);
            throw new ProviderUnavailableException(providerCode, "circuit open");
        }
        MDC.put(Mdc.PROVIDER, providerCode);
        long start = System.nanoTime();
        try {
            T result = action.apply(provider, account);
            long elapsed = System.nanoTime() - start;
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            health.recordLatency(providerCode, Duration.ofNanos(elapsed));
            record(providerCode, operation, "ok", elapsed);
            return result;
        } catch (ProviderCredentialsException e) {
            breaker.releasePermission();
            record(providerCode, operation, "credentials_rejected", System.nanoTime() - start);
            log.warn("PSP rejected the credentials of merchant account {}: {}", account.id(), e.getMessage());
            throw e;
        } catch (ProviderRefusedException e) {
            long elapsed = System.nanoTime() - start;
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            record(providerCode, operation, "refused", elapsed);
            throw e;
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            long elapsed = System.nanoTime() - start;
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, e);
            record(providerCode, operation, e instanceof ProviderTimeoutException ? "timeout" : "unavailable", elapsed);
            log.warn("Provider call failed: operation={} error={}", operation, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            long elapsed = System.nanoTime() - start;
            ProviderTimeoutException unknown = new ProviderTimeoutException(providerCode,
                    "unexpected adapter error during " + operation, e);
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, unknown);
            record(providerCode, operation, "error", elapsed);
            log.error("Unexpected provider adapter error: operation={}", operation, e);
            throw unknown;
        } finally {
            MDC.remove(Mdc.PROVIDER);
        }
    }

    private void record(String providerCode, String operation, String result, long nanos) {
        Timer.builder("pg.provider.call")
                .tag("provider", providerCode)
                .tag("operation", operation)
                .tag("result", result)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
