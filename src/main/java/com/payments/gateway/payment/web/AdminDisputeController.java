package com.payments.gateway.payment.web;

import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.DisputeResponse;
import com.payments.gateway.payment.application.DisputeDeadlineJob;
import com.payments.gateway.shared.error.GatewayException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations' view of disputes whose evidence is due (LLD §22.4), to contact the merchants. */
@RestController
@RequestMapping("/admin/v1/disputes")
public class AdminDisputeController {

    /** A dispute with the merchant it belongs to. */
    public record AdminDispute(String merchantId, DisputeResponse dispute) {
    }

    private final DisputeDeadlineJob deadlines;
    private final PaymentMapper mapper;

    public AdminDisputeController(DisputeDeadlineJob deadlines, PaymentMapper mapper) {
        this.deadlines = deadlines;
        this.mapper = mapper;
    }

    @GetMapping
    public Map<String, List<AdminDispute>> list(@RequestParam(value = "evidence_due") boolean evidenceDue,
                                                @RequestParam(value = "merchant_id", required = false) String merchantId,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (!evidenceDue) {
            throw GatewayException.validation("evidence_due", "must be true: only disputes with evidence due are listed");
        }
        if (limit < 1 || limit > 500) {
            throw GatewayException.validation("limit", "must be between 1 and 500");
        }
        return Map.of("data", deadlines.listDue(merchantId, limit).stream()
                .map(dispute -> new AdminDispute(dispute.merchantId(), mapper.toResponse(dispute)))
                .toList());
    }
}
