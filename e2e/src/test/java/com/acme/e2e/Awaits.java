package com.acme.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

/** Waiting for background exchange (send worker, feed poller and the emulator's executor each run every ~1 s). */
public final class Awaits {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);

    private Awaits() {
    }

    /** Polls the request card until it has a final business result; returns that card. */
    public static JsonNode finalCard(E2eClient client, String requestId) {
        return finalCard(client, requestId, TIMEOUT);
    }

    /** Polls the request card until it has a final business result within {@code timeout}; returns that card. */
    public static JsonNode finalCard(E2eClient client, String requestId, Duration timeout) {
        return card(client, requestId, "a final business result", Awaits::isFinal, timeout);
    }

    /** Polls the request card until {@code condition} holds; returns that card. */
    public static JsonNode card(E2eClient client, String requestId, String what, Predicate<JsonNode> condition,
                                Duration timeout) {
        return until("request " + requestId + ": " + what, () -> client.get("/api/requests/" + requestId).body(),
                condition, timeout);
    }

    /** Calls {@code probe} until its value satisfies {@code done}; returns that value. */
    public static <T> T until(String what, Supplier<T> probe, Predicate<T> done, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        T last = probe.get();
        while (!done.test(last)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Not reached within " + timeout.toSeconds() + " s: " + what
                        + "; last: " + last);
            }
            sleep(POLL_INTERVAL);
            last = probe.get();
        }
        return last;
    }

    public static boolean isFinal(JsonNode card) {
        String status = card.get("businessStatus").asString();
        return status.equals("SUCCEEDED") || status.equals("REJECTED");
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
