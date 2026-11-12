package com.acme.e2e;

import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.JsonNode;

/** Waiting for background exchange (send worker, feed poller and the emulator's executor each run every ~1 s). */
public final class Awaits {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Awaits() {
    }

    /** Polls the request card until it has a final business result; returns that card. */
    public static JsonNode finalCard(E2eClient client, String requestId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        JsonNode card = null;
        while (Instant.now().isBefore(deadline)) {
            card = client.get("/api/requests/" + requestId).body();
            String status = card.get("businessStatus").asString();
            if (status.equals("SUCCEEDED") || status.equals("REJECTED")) {
                return card;
            }
            sleep();
        }
        throw new AssertionError("Request " + requestId + " has no final result within " + TIMEOUT.toSeconds()
                + " s; last card: " + card);
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
