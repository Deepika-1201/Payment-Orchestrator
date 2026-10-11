package com.payments.gateway.billing.web;

import com.payments.gateway.billing.BillingAmounts.Price;
import com.payments.gateway.billing.BillingPlan;
import com.payments.gateway.billing.BillingEvent;
import com.payments.gateway.billing.BillingDocuments.Invoice;
import com.payments.gateway.billing.BillingDocuments.CreditNote;
import com.payments.gateway.billing.BillingService;
import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/merchants/{merchantId}")
public class AdminBillingController {

    public record FeeRequest(@NotNull @Min(0) @Max(1_000_000_000_000L) Long fixedAmount,
                             @NotNull @Min(0) @Max(10_000) Integer rateBps) {
    }

    public record PlanRequest(@NotBlank @Size(max = 200) String name, Instant effectiveAt,
                              @NotNull @Size(max = 10) Map<@NotBlank String, @NotNull @Valid FeeRequest> paymentFees,
                              @Size(max = 10) Map<@NotBlank String, @NotNull @Valid FeeRequest> refundFees,
                              @Min(0) @Max(1_000_000_000_000L) Long monthlyMinimum) {
    }

    private final BillingService billing;

    public record IssueRequest(@NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month) {
    }

    public record PriceRequest(@NotNull @Min(0) @Max(1_000_000_000_000L) Long amount, @NotBlank @Size(max = 1000) String reason) {
    }

    public record CreditLineRequest(@NotBlank String invoiceLineId, @NotNull @Min(1) Long amount) {
    }

    public record CreditRequest(@NotBlank @Size(max = 128) String reference, @NotBlank @Size(max = 1000) String reason,
                                 @NotEmpty @Size(max = 100) List<@NotNull @Valid CreditLineRequest> lines) {
    }

    public AdminBillingController(BillingService billing) {
        this.billing = billing;
    }

    @PostMapping("/billing-plans")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingPlan publish(@PathVariable String merchantId, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                               @Valid @RequestBody PlanRequest request) {
        return billing.publish(merchantId, new BillingService.PublishPlan(request.name(), request.effectiveAt(),
                prices(request.paymentFees()), prices(request.refundFees()),
                request.monthlyMinimum() == null ? 0 : request.monthlyMinimum()), actor);
    }

    @GetMapping("/billing-plans")
    public Map<String, List<BillingPlan>> plans(@PathVariable String merchantId) {
        return Map.of("data", billing.plans(merchantId));
    }

    @PostMapping("/invoices")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Invoice issue(@PathVariable String merchantId, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                         @Valid @RequestBody IssueRequest request) {
        return billing.issue(merchantId, YearMonth.parse(request.month()), actor);
    }

    @GetMapping("/invoices")
    public Map<String, List<Invoice>> invoices(@PathVariable String merchantId) {
        return Map.of("data", billing.invoices(merchantId));
    }

    @GetMapping("/invoices/{invoiceId}")
    public Invoice invoice(@PathVariable String merchantId, @PathVariable String invoiceId) {
        return billing.invoice(merchantId, invoiceId);
    }

    @GetMapping("/billing-events")
    public Map<String, List<BillingEvent>> events(@PathVariable String merchantId,
            @RequestParam(defaultValue = "false") boolean unbilled, @RequestParam(defaultValue = "100") int limit) {
        return Map.of("data", billing.events(merchantId, unbilled, limit));
    }

    @PostMapping("/billing-events/{eventId}/price")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public BillingEvent price(@PathVariable String merchantId, @PathVariable String eventId,
            @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @Valid @RequestBody PriceRequest request) {
        return billing.price(merchantId, eventId, request.amount(), request.reason(), actor);
    }

    @PostMapping("/invoices/{invoiceId}/credit-notes")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreditNote credit(@PathVariable String merchantId, @PathVariable String invoiceId,
            @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @Valid @RequestBody CreditRequest request) {
        return billing.credit(merchantId, invoiceId, new BillingService.CreateCredit(request.reference(), request.reason(),
                request.lines().stream().map(line -> new BillingService.CreditAmount(line.invoiceLineId(), line.amount())).toList()), actor);
    }

    @GetMapping("/invoices/{invoiceId}/credit-notes")
    public Map<String, List<CreditNote>> credits(@PathVariable String merchantId, @PathVariable String invoiceId) {
        return Map.of("data", billing.credits(merchantId, invoiceId));
    }

    private static Map<MethodType, Price> prices(Map<String, FeeRequest> fees) {
        Map<MethodType, Price> result = new EnumMap<>(MethodType.class);
        if (fees != null) {
            fees.forEach((method, fee) -> {
                MethodType type = WireEnums.parse(MethodType.class, method, "method");
                if (result.put(type, new Price(fee.fixedAmount(), fee.rateBps())) != null) {
                    throw com.payments.gateway.shared.error.GatewayException.validation("method", "is listed more than once");
                }
            });
        }
        return result;
    }
}