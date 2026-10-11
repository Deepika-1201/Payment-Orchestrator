package com.payments.gateway.payout.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.payout.BankReconciliationService;
import com.payments.gateway.payout.BankStatement;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/treasury/statements")
public class AdminBankStatementController {
    public record EntryRequest(@NotBlank String bankEntryId, @NotBlank String kind, @NotBlank String referenceId,
                                long amount, @NotNull Instant bookedAt) {
    }

    public record ImportRequest(@NotBlank String reference, @NotBlank String accountReference, @NotNull Instant from, @NotNull Instant to,
                                 long openingBalance, long closingBalance, @NotNull @Size(max = 10000) List<@Valid EntryRequest> entries) {
    }

    public record TransitRequest(@NotBlank String kind, @NotBlank String referenceId) {
    }

    public record ReconcileRequest(@NotNull @Size(max = 10000) List<@Valid TransitRequest> inTransit) {
    }

    private final BankReconciliationService bank;

    public AdminBankStatementController(BankReconciliationService bank) {
        this.bank = bank;
    }

    @PostMapping
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BankReconciliationService.Imported ingest(@Valid @RequestBody ImportRequest request,
            @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        List<BankStatement.Entry> entries = request.entries().stream().map(entry -> new BankStatement.Entry(entry.bankEntryId(),
                WireEnums.parse(BankStatement.Kind.class, entry.kind(), "kind"), entry.referenceId(), entry.amount(), entry.bookedAt())).toList();
        return bank.ingest(new BankStatement(null, request.reference(), request.accountReference(), request.from(), request.to(),
                request.openingBalance(), request.closingBalance(), entries, null), actor);
    }

    @GetMapping("/{id}")
    public BankStatement get(@PathVariable String id) {
        return bank.get(id);
    }

    @PostMapping("/{id}/reconcile")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public BankReconciliationService.Result reconcile(@PathVariable String id, @Valid @RequestBody ReconcileRequest request) {
        return bank.reconcile(id, request.inTransit().stream().map(item -> new BankReconciliationService.Transit(
                WireEnums.parse(BankStatement.Kind.class, item.kind(), "kind"), item.referenceId())).toList());
    }
}