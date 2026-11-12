package com.acme.dispatch.idempotency;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.exchange.PayloadHasher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} handling for mutating actions (NF04, design §3): the server keeps
 * "user + key → operation + request hash + response", so a repeated call with the same key and the
 * same request returns the stored response instead of producing a second business result, and the
 * same key with a different request (or for a different operation) is rejected with
 * {@code 422 IDEMPOTENCY_KEY_REUSED}. Keys are scoped per user.
 *
 * <p><b>Transactions.</b> {@link #execute} requires an existing transaction
 * ({@link Propagation#MANDATORY}): the key row, the business action and the stored response are
 * written in the caller's transaction, so they commit or roll back together. If the action throws,
 * the key row is rolled back too and the client can retry with the same key.
 *
 * <p><b>Concurrency.</b> The key row is claimed with
 * {@code INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING}. Under READ COMMITTED a parallel
 * duplicate blocks on the primary key until the first transaction finishes: if it commits, the
 * duplicate's insert affects 0 rows and the duplicate reads the committed row and replays it; if it
 * rolls back, the duplicate's insert succeeds and it runs the action itself. So the action runs at
 * most once per committed key.
 */
@Service
public class IdempotencyService {

    public static final int MAX_KEY_LENGTH = 100;

    /** Canonical JSON for hashing: bean properties, map entries and tree fields sorted by name. */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // Records and @JsonCreator types would otherwise keep constructor order.
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final BusinessCalendar calendar;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendar = calendar;
    }

    /**
     * Runs {@code action} once per {@code (userId, key)}; a repeat with the same operation and request
     * returns the stored response deserialized as {@code bodyType} with {@code replayed = true}.
     *
     * @throws ApiException {@code IDEMPOTENCY_KEY_REQUIRED} for a blank key, {@code VALIDATION_FAILED} for a too long key,
     *                      {@code IDEMPOTENCY_KEY_REUSED} if the key was used for another operation or request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentOutcome<T> execute(UUID userId, String key, String operation, Object request,
                                            Class<T> bodyType, Supplier<IdempotentResponse<T>> action) {
        validateKey(key);
        String requestHash = requestHash(request);

        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (user_id, idem_key, operation, request_hash, created_at)
                        VALUES (:userId, :key, :operation, :requestHash, :now)
                        ON CONFLICT (user_id, idem_key) DO NOTHING""")
                .param("userId", userId)
                .param("key", key)
                .param("operation", operation)
                .param("requestHash", requestHash)
                .param("now", toSql(calendar.now()))
                .update();

        if (inserted == 1) {
            IdempotentResponse<T> response = Objects.requireNonNull(action.get(), "action returned null");
            jdbc.sql("""
                            UPDATE idempotency_key
                            SET response_status = :status, response_body = CAST(:body AS jsonb), resource_id = :resourceId
                            WHERE user_id = :userId AND idem_key = :key""")
                    .param("status", response.status())
                    .param("body", response.body() == null ? null : json.writeValueAsString(response.body()))
                    .param("resourceId", response.resourceId())
                    .param("userId", userId)
                    .param("key", key)
                    .update();
            return new IdempotentOutcome<>(response.status(), response.body(), response.resourceId(), false);
        }

        StoredKey stored = jdbc.sql("""
                        SELECT operation, request_hash, response_status, response_body::text AS response_body, resource_id
                        FROM idempotency_key
                        WHERE user_id = :userId AND idem_key = :key""")
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> new StoredKey(
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getObject("resource_id", UUID.class)))
                .single();

        if (!stored.operation().equals(operation) || !stored.requestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key '" + key + "' was already used with a different request");
        }
        if (stored.status() == null) {
            // Cannot happen: the row and its response are written in one transaction.
            throw new IllegalStateException("Idempotency key has no stored response: " + key);
        }
        T body = stored.body() == null ? null : json.readValue(stored.body(), bodyType);
        return new IdempotentOutcome<>(stored.status(), body, stored.resourceId(), true);
    }

    /** SHA-256 (64 lowercase hex chars) of the canonical JSON of {@code request}; field order never matters. */
    public static String requestHash(Object request) {
        return PayloadHasher.sha256(CANONICAL.writeValueAsString(request));
    }

    static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            // The same code the API returns for a missing header: one rule, one code.
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Send a unique, non-empty Idempotency-Key header with this request");
        }
        if (key.codePointCount(0, key.length()) > MAX_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    private record StoredKey(String operation, String requestHash, Integer status, String body, UUID resourceId) {
    }
}
