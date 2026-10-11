package com.payments.gateway.payout.mock;

import com.payments.gateway.payout.PayoutWebhookService;
import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.WireEnums;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!prod & (local | test)")
@RequestMapping("/simulator/payouts")
public class MockPayoutController {
    public record Move(String outcome, boolean webhook) {
    }

    private final MockPayoutProvider provider;
    private final PayoutWebhookService webhooks;
    private final JsonCodec json;

    public MockPayoutController(MockPayoutProvider provider, PayoutWebhookService webhooks, JsonCodec json) {
        this.provider = provider;
        this.webhooks = webhooks;
        this.json = json;
    }

    @PostMapping("/{id}/outcome")
    public PayoutProvider.Result move(@PathVariable String id, @RequestBody Move request) {
        var result = provider.move(id, WireEnums.parse(PayoutProvider.Outcome.class, request.outcome(), "outcome"));
        if (request.webhook()) {
            String body = json.write(new PayoutProvider.Event(Ids.newId("pevt"), id, result));
            webhooks.receive(MockPayoutProvider.CODE, Map.of("X-Payout-Signature", provider.sign(body)), body);
        }
        return result;
    }
}