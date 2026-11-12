package com.acme.dispatch.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.CarrierUnavailableException;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitRequest;
import com.acme.dispatch.carrier.SubmitResult;

import tools.jackson.databind.JsonNode;

/**
 * Programmable carrier for integration tests (replaces the HTTP client). Submits are recorded and answered
 * from a queue of results (default: acknowledged); the feed returns whatever the test put into it.
 * The context is shared, so tests call {@link #reset()} in {@code @BeforeEach}.
 */
public class FakeCarrier implements CarrierClient {

    public static final SubmitResult ACKNOWLEDGED = new SubmitResult(SubmitOutcome.ACKNOWLEDGED, 202, null);

    private final List<SubmitRequest> submitted = new CopyOnWriteArrayList<>();
    private final Deque<SubmitResult> answers = new ArrayDeque<>();
    private final List<JsonNode> feed = new ArrayList<>();
    private boolean feedUnavailable;
    private volatile Runnable duringSubmit = () -> { };

    public synchronized void reset() {
        submitted.clear();
        answers.clear();
        feed.clear();
        feedUnavailable = false;
        duringSubmit = () -> { };
    }

    /** Runs while a submit is "in flight", e.g. to deliver a result before the sender records its outcome. */
    public void duringSubmit(Runnable action) {
        this.duringSubmit = action;
    }

    /** Answers for the next submits, in order; after they run out every submit is acknowledged. */
    public synchronized void answer(SubmitResult... results) {
        answers.addAll(List.of(results));
    }

    public List<SubmitRequest> submitted() {
        return List.copyOf(submitted);
    }

    /** Appends entries to the feed; each must have a {@code feedSeq}. */
    public synchronized void publish(JsonNode... entries) {
        feed.addAll(List.of(entries));
    }

    public synchronized void feedUnavailable(boolean unavailable) {
        this.feedUnavailable = unavailable;
    }

    @Override
    public SubmitResult submit(SubmitRequest request) {
        submitted.add(request);
        duringSubmit.run();
        synchronized (this) {
            return answers.isEmpty() ? ACKNOWLEDGED : answers.poll();
        }
    }

    @Override
    public synchronized List<JsonNode> results(long after, int limit) {
        if (feedUnavailable) {
            throw new CarrierUnavailableException("fake carrier is unavailable", null);
        }
        return feed.stream()
                .filter(entry -> !entry.has("feedSeq") || entry.get("feedSeq").asLong() > after)
                .limit(limit)
                .toList();
    }
}
