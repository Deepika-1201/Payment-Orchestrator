package com.payments.gateway.idempotency;

import com.payments.gateway.idempotency.IdempotencyRepository.StoredRecord;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.json.JsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * API-level idempotency (ADR-006): replays stored responses, rejects key reuse with a different request, and
 * serializes concurrent duplicates. Unexpected failures release the key so a retry re-executes; domain-level
 * idempotency makes that safe.
 */
@Service
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("[\\x21-\\x7E]{1,255}");

    public record Result(int status, Object body) {
    }

    record StoredError(String code, String detail) {
    }

    private final IdempotencyRepository repository;
    private final IdempotencyProperties properties;
    private final JsonCodec json;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, IdempotencyProperties properties, JsonCodec json,
                              Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public ResponseEntity<String> execute(String merchantId, String key, String operation, Object request,
                                          Supplier<Result> action) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw GatewayException.validation("Idempotency-Key", "must be 1-255 visible ASCII characters");
        }
        byte[] requestHash = Hashing.sha256(operation + "\n" + json.write(request));
        Instant now = clock.instant();
        if (!repository.tryInsert(merchantId, key, requestHash, now, now.plus(properties.lease()), now.plus(properties.ttl()))) {
            StoredRecord existing = repository.find(merchantId, key).orElse(null);
            if (existing == null) {
                throw inProgress();
            }
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw new GatewayException(ErrorCode.IDEMPOTENCY_KEY_REUSE,
                        "This Idempotency-Key was used for a different request; use a new key");
            }
            if (existing.completed()) {
                return replay(existing);
            }
            if (existing.lockedUntil() != null && existing.lockedUntil().isAfter(now)) {
                throw inProgress();
            }
            if (!repository.takeOver(merchantId, key, now, now.plus(properties.lease()))) {
                throw inProgress();
            }
        }
        try {
            Result result = action.get();
            String body = json.write(result.body());
            repository.complete(merchantId, key, result.status(), body);
            return respond(result.status(), body, false);
        } catch (GatewayException e) {
            if (e.code().isClientError() && e.code() != ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS) {
                repository.complete(merchantId, key, e.code().httpStatus(),
                        json.write(new StoredError(e.code().code(), e.getMessage())));
            } else {
                repository.delete(merchantId, key);
            }
            throw e;
        } catch (RuntimeException e) {
            repository.delete(merchantId, key);
            throw e;
        }
    }

    public int purgeExpired(int limit) {
        return repository.deleteExpired(clock.instant(), limit);
    }

    private ResponseEntity<String> replay(StoredRecord record) {
        int status = record.responseStatus() == null ? 500 : record.responseStatus();
        if (status >= 400) {
            StoredError error = json.read(record.responseBody(), StoredError.class);
            throw new GatewayException(ErrorCode.valueOf(error.code().toUpperCase(Locale.ROOT)), error.detail());
        }
        return respond(status, record.responseBody(), true);
    }

    private static ResponseEntity<String> respond(int status, String body, boolean replayed) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, String.valueOf(replayed))
                .body(body);
    }

    private static GatewayException inProgress() {
        return new GatewayException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                "A request with this Idempotency-Key is being processed; retry shortly", List.of(), 1);
    }
}
