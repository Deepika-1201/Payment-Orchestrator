package com.payments.gateway.billing;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

public final class BillingDocuments {

    private BillingDocuments() {
    }

    public record InvoiceLine(String id, String kind, String planId, String method, long quantity, long amount, long tax,
                              Instant firstActivityAt, Instant lastActivityAt) {
    }

    public record Invoice(String id, String object, String merchantId, String number, YearMonth month, String currency,
                          long subtotal, long tax, long total, String collectionMode, Instant issuedAt, List<InvoiceLine> lines) {
        public Invoice {
            lines = List.copyOf(lines);
        }
    }

    public record CreditLine(String id, String invoiceLineId, long amount, long tax) {
    }

    public record CreditNote(String id, String object, String merchantId, String invoiceId, String reference, String reason,
                             String number, String currency, long subtotal, long tax, long total, Instant issuedAt,
                             List<CreditLine> lines) {
        public CreditNote {
            lines = List.copyOf(lines);
        }
    }
}