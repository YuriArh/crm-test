package com.acme.dispatch.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.CarrierUnavailableException;
import com.acme.dispatch.carrier.LookupResult;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitRequest;
import com.acme.dispatch.carrier.SubmitResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Programmable carrier for integration tests (replaces the HTTP client). Submits are recorded and answered
 * from a queue of results (default: acknowledged); the feed returns whatever the test put into it; a lookup answers
 * what the test set for the operation (default: not found). The context is shared, so tests call {@link #reset()} in
 * {@code @BeforeEach}.
 */
public class FakeCarrier implements CarrierClient {

    public static final SubmitResult ACKNOWLEDGED = new SubmitResult(SubmitOutcome.ACKNOWLEDGED, 202, null);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<SubmitRequest> submitted = new CopyOnWriteArrayList<>();
    private final Deque<SubmitResult> answers = new ArrayDeque<>();
    private final List<JsonNode> feed = new ArrayList<>();
    private final Map<UUID, LookupResult> lookups = new HashMap<>();
    private final List<UUID> lookedUp = new CopyOnWriteArrayList<>();
    private boolean feedUnavailable;
    private boolean lookupUnavailable;
    private volatile Runnable duringSubmit = () -> { };
    private volatile Runnable duringLookup = () -> { };

    public synchronized void reset() {
        submitted.clear();
        answers.clear();
        feed.clear();
        lookups.clear();
        lookedUp.clear();
        feedUnavailable = false;
        lookupUnavailable = false;
        duringSubmit = () -> { };
        duringLookup = () -> { };
    }

    /** Runs while a submit is "in flight", e.g. to deliver a result before the sender records its outcome. */
    public void duringSubmit(Runnable action) {
        this.duringSubmit = action;
    }

    /** Runs while a lookup is "in flight", e.g. to apply a result from the feed before reconciliation decides. */
    public void duringLookup(Runnable action) {
        this.duringLookup = action;
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

    /** What a lookup of this operation answers from now on. */
    public synchronized void lookupAnswer(UUID operationId, LookupResult answer) {
        lookups.put(operationId, answer);
    }

    public synchronized void lookupUnavailable(boolean unavailable) {
        this.lookupUnavailable = unavailable;
    }

    /** The operations looked up so far, in order. */
    public List<UUID> lookedUp() {
        return List.copyOf(lookedUp);
    }

    /** The carrier received the operation and has not executed it. */
    public static LookupResult accepted(UUID operationId) {
        return state(operationId, "ACCEPTED", null);
    }

    /** The carrier executed the operation with this result (a feed entry; its {@code feedSeq} is dropped). */
    public static LookupResult executed(UUID operationId, JsonNode result) {
        ObjectNode copy = ((ObjectNode) result).deepCopy();
        copy.remove("feedSeq");
        return state(operationId, "EXECUTED", copy);
    }

    public static LookupResult notFound(UUID operationId) {
        String raw = "{\"code\":\"OPERATION_NOT_FOUND\",\"message\":\"Operation " + operationId + " is not known\"}";
        return new LookupResult.NotFound("OPERATION_NOT_FOUND", "Operation " + operationId + " is not known", raw);
    }

    private static LookupResult state(UUID operationId, String state, JsonNode result) {
        ObjectNode body = JSON.createObjectNode();
        body.put("operationId", operationId.toString());
        body.put("state", state);
        body.put("receivedAt", "2026-11-12T09:00:01Z");
        if (result == null) {
            body.putNull("executedAt");
            body.putNull("result");
        } else {
            body.put("executedAt", result.path("occurredAt").asString());
            body.set("result", result);
        }
        return new LookupResult.Found(state, result, JSON.writeValueAsString(body));
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

    @Override
    public LookupResult lookup(UUID operationId) {
        lookedUp.add(operationId);
        duringLookup.run();
        synchronized (this) {
            if (lookupUnavailable) {
                throw new CarrierUnavailableException("fake carrier is unavailable", null);
            }
            return lookups.getOrDefault(operationId, notFound(operationId));
        }
    }
}
