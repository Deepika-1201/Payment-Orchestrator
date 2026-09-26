package com.payments.gateway.ledger.web;

import com.payments.gateway.ledger.EntryDirection;
import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.TransactionView;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.shared.web.WireEnums;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/ledger")
public class AdminLedgerController {

    public record BalanceResponse(String provider, String account, String currency, long debits, long credits,
                                  long balance) {
    }

    public record EntryResponse(String account, String direction, long amount, String currency) {
    }

    public record TransactionResponse(String id, String type, String provider, String referenceType, String referenceId,
                                      String description, Instant occurredAt, List<EntryResponse> entries) {
    }

    private final LedgerService ledger;

    public AdminLedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/balances")
    public Map<String, List<BalanceResponse>> balances(@RequestParam("merchant_id") String merchantId,
                                                       @RequestParam(value = "provider", required = false) String provider) {
        return Map.of("data", ledger.balances(merchantId, provider).stream()
                .map(b -> new BalanceResponse(b.providerCode(), wire(b.account()), b.currency(), b.debits(), b.credits(), b.balance()))
                .toList());
    }

    @GetMapping("/transactions")
    public Map<String, List<TransactionResponse>> transactions(@RequestParam("reference_id") String referenceId) {
        return Map.of("data", ledger.transactionsForReference(referenceId).stream().map(AdminLedgerController::toResponse).toList());
    }

    private static TransactionResponse toResponse(TransactionView view) {
        return new TransactionResponse(view.id(), WireEnums.wire(view.type()), view.providerCode(), view.referenceType(),
                view.referenceId(), view.description(), view.occurredAt(), view.entries().stream()
                .map(e -> new EntryResponse(wire(e.account()), direction(e.direction()), e.amount(), e.currency()))
                .toList());
    }

    private static String wire(LedgerAccountType account) {
        return WireEnums.wire(account);
    }

    private static String direction(EntryDirection direction) {
        return WireEnums.wire(direction);
    }
}
