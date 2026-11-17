package com.acme.dispatch.history;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

/**
 * The position after the last entry of a page: its time (to the microsecond, as stored), kind and id. Opaque to
 * clients.
 */
public record HistoryCursor(Instant at, HistoryKind kind, UUID id) {

    private static final long MICROS = 1_000_000L;

    public String encode() {
        long micros = at.getEpochSecond() * MICROS + at.getNano() / 1_000;
        String text = micros + ":" + kind.name() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ApiException {@code VALIDATION_FAILED} if it is not a cursor this API gave */
    public static HistoryCursor parse(String value) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("three parts expected");
            }
            long micros = Long.parseLong(parts[0]);
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, MICROS), Math.floorMod(micros, MICROS) * 1_000);
            return new HistoryCursor(at, HistoryKind.valueOf(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "before is not a cursor of this history");
        }
    }
}
