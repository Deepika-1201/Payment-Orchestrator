package com.payments.gateway.payout.mock;

import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.KeyRingCiphertexts;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & (local | test)")
public class MockPayoutProvider implements PayoutProvider, KeyRingCiphertexts {
    public static final String CODE = "MOCK_PAYOUT";
    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final JsonCodec json;
    private final Clock clock;
    private final String webhookSecret;

    public MockPayoutProvider(JdbcClient jdbc, SecretCipher cipher, JsonCodec json, Clock clock,
                             @Value("${pg.payouts.mock.webhook-secret:local-payout-webhook-secret}") String webhookSecret) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String sourceAccount() {
        return "MOCK_ESCROW";
    }

    @Override
    public void validate(Instruction instruction) {
        if (!instruction.amount().inSettlementCurrency() || instruction.amount().amount() <= 0
                || !sourceAccount().equals(instruction.sourceAccount())) {
            throw new IllegalArgumentException("Invalid mock payout instruction");
        }
    }

    @Override
    public Result create(Instruction instruction) {
        validate(instruction);
        String encoded = json.write(instruction);
        byte[] hash = Hashing.sha256(encoded);
        long scenario = instruction.amount().amount() % 100;
        if (scenario == 5) {
            return Result.unknown("simulated_timeout_before_processing");
        }
        String outcome = scenario == 3 ? Outcome.REJECTED.name() : scenario == 4 ? Outcome.PENDING.name() : Outcome.SUCCEEDED.name();
        jdbc.sql("""
                INSERT INTO simulated_payouts (id, provider_reference, instruction_hash, instruction_enc, outcome, created_at)
                VALUES (:id, :reference, :hash, :instruction, :outcome, :now)
                ON CONFLICT (id) DO UPDATE SET create_calls = simulated_payouts.create_calls + 1
                """).param("id", instruction.id()).param("reference", "mock_" + instruction.id()).param("hash", hash)
                .param("instruction", cipher.encrypt(encoded, context(instruction.id()))).param("outcome", outcome)
                .param("now", Sql.ts(clock.instant())).update();
        byte[] stored = jdbc.sql("SELECT instruction_hash FROM simulated_payouts WHERE id = :id").param("id", instruction.id())
                .query((row, index) -> row.getBytes("instruction_hash")).single();
        if (!MessageDigest.isEqual(hash, stored)) {
            throw new IllegalArgumentException("Payout idempotency key reused with a different instruction");
        }
        return scenario == 1 ? Result.unknown("simulated_timeout_after_processing") : result(instruction.id());
    }

    @Override
    public Result fetch(Instruction instruction, String providerReference) {
        if (jdbc.sql("UPDATE simulated_payouts SET fetch_calls = fetch_calls + 1 WHERE id = :id")
                .param("id", instruction.id()).update() == 0) {
            return new Result(Outcome.NOT_FOUND, null, instruction.id(), null, null, null, "not_found");
        }
        return result(instruction.id());
    }

    public Result move(String id, Outcome outcome) {
        if (jdbc.sql("UPDATE simulated_payouts SET outcome = :outcome WHERE id = :id").param("outcome", outcome.name()).param("id", id).update() != 1) {
            throw new IllegalArgumentException("Unknown simulated payout");
        }
        return result(id);
    }

    public int createCalls(String id) {
        return jdbc.sql("SELECT create_calls FROM simulated_payouts WHERE id = :id").param("id", id).query(Integer.class).optional().orElse(0);
    }

    public Result result(String id) {
        return jdbc.sql("SELECT * FROM simulated_payouts WHERE id = :id").param("id", id).query((row, index) -> {
            Instruction instruction = json.read(cipher.decrypt(row.getBytes("instruction_enc"), context(id)), Instruction.class);
            Outcome outcome = Outcome.valueOf(row.getString("outcome"));
            return new Result(outcome, row.getString("provider_reference"), id, instruction.amount(), instruction.destination(),
                    outcome == Outcome.SUCCEEDED || outcome == Outcome.RETURNED ? "BANK_" + id : null,
                    outcome == Outcome.REJECTED ? "simulated_rejection" : null);
        }).single();
    }

    @Override
    public Event parseWebhook(Map<String, String> headers, String body) {
        String signature = headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("X-Payout-Signature"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (!Hashing.constantTimeEquals(signature, sign(body))) {
            throw new InvalidWebhook("Invalid payout signature");
        }
        return json.read(body, Event.class);
    }

    public String sign(String body) {
        return Hashing.hmacSha256Hex(webhookSecret, body);
    }

    private static String context(String id) {
        return "simulated_payout:" + id;
    }

    @Override
    public List<Stored> keyRingCiphertexts() {
        return jdbc.sql("SELECT id, instruction_enc FROM simulated_payouts ORDER BY id")
                .query((row, index) -> new Stored(row.getString("id"), row.getBytes("instruction_enc"), context(row.getString("id")))).list();
    }

    @Override
    public boolean swapKeyRingCiphertext(String id, byte[] expected, byte[] replacement) {
        return jdbc.sql("UPDATE simulated_payouts SET instruction_enc = :after WHERE id = :id AND instruction_enc = :before")
                .param("after", replacement).param("id", id).param("before", expected).update() == 1;
    }
}