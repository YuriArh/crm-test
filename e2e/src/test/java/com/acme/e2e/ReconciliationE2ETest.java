package com.acme.e2e;

import static com.acme.e2e.CarrierClient.DELAYED_RESULT_DELAY;
import static com.acme.e2e.CarrierFaultsE2ETest.uniqueCargo;
import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import com.acme.e2e.CarrierClient.Faults;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation on the running stack ({@code docs/specs/reconciliation.md} §3): «Сверить сейчас» takes over a result
 * the carrier has not published in its feed yet, finds nothing to fix on a finished request, keeps both sides of a
 * conflict after the operator's decision ({@code docs/specs/conflict-history.md}), and is an operator's action only.
 */
class ReconciliationE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration TIMEOUT = Duration.ofSeconds(90);

    /** The carrier's new result; the emulator never makes this number up for a short run. */
    static final String CARRIER_TRACKING = "TRK-FFFFFFFF";

    final CarrierClient carrier = new CarrierClient();
    final CarrierDatabase carrierDatabase = new CarrierDatabase();
    final E2eClient initiator = E2eClient.signedInAs("north.initiator");
    final E2eClient operator = E2eClient.signedInAs("north.operator");

    @BeforeEach
    void allModesOff() {
        carrier.faultsOff();
    }

    @AfterEach
    void allModesOffAgain() {
        carrier.faultsOff();
    }

    /**
     * With {@code delayed} the carrier executes at once but publishes the result {@link
     * CarrierClient#DELAYED_RESULT_DELAY} later; reconciliation sees it through {@code GET /operations} first. The
     * feed entry that comes later changes nothing.
     */
    @Test
    void reconcileCatchesUpDelayedResult() {
        carrier.setFaults(Faults.DELAYED);
        String id = create(uniqueCargo("reconcile delayed"));
        JsonNode acknowledged = Awaits.card(operator, id, "ACKNOWLEDGED",
                card -> card.get("deliveryStatus").asString().equals("ACKNOWLEDGED"), TIMEOUT);
        carrier.faultsOff();
        String operationId = acknowledged.get("operationId").asString();
        Awaits.until("operation " + operationId + " executed", () -> carrier.adminOperation(operationId),
                op -> op.get("state").asString().equals("EXECUTED"), TIMEOUT);
        assertThat(operator.get("/api/requests/" + id).body().get("businessStatus").asString())
                .as("the feed has not brought it yet").isEqualTo("AWAITING");

        E2eClient.Response reconciled = operator.post("/api/requests/" + id + "/reconcile", null,
                idempotencyKey(newKey()));

        assertThat(reconciled.status()).isEqualTo(200);
        assertThat(reconciled.body().get("outcome").asString()).isEqualTo("EXTERNAL_AHEAD");
        assertThat(reconciled.body().get("trigger").asString()).isEqualTo("MANUAL");
        JsonNode card = operator.get("/api/requests/" + id).body();
        assertThat(Awaits.isFinal(card)).as("the result is in the card right after the response").isTrue();
        assertThat(card.get("businessStatus").asString())
                .isEqualTo(reconciled.body().get("carrierResult").get("status").asString());
        long version = card.get("version").asLong();
        assertThat(carrier.adminOperation(operationId).get("executionCount").asInt()).isEqualTo(1);

        Awaits.until("the delayed result published", () -> carrier.adminOperation(operationId),
                op -> op.get("resultPublishAt").isNull(), DELAYED_RESULT_DELAY.plus(TIMEOUT));
        Awaits.sleep(Duration.ofSeconds(5)); // a few feed passes of dispatch
        JsonNode after = operator.get("/api/requests/" + id).body();
        assertThat(after.get("version").asLong()).as("the feed entry changed nothing").isEqualTo(version);
        assertThat(after.get("problems").size()).isZero();
    }

    @Test
    void reconcileOfFinishedRequestIsMatch() {
        String id = create(uniqueCargo("reconcile finished"));
        Awaits.finalCard(operator, id);

        E2eClient.Response reconciled = operator.post("/api/requests/" + id + "/reconcile", null,
                idempotencyKey(newKey()));

        assertThat(reconciled.status()).isEqualTo(200);
        assertThat(reconciled.body().get("outcome").asString()).isEqualTo("MATCH");
        JsonNode card = operator.get("/api/requests/" + id).body();
        assertThat(card.get("reconciliations").get(0).get("id")).isEqualTo(reconciled.body().get("id"));
        assertThat(card.get("actions").valueStream().map(JsonNode::asString)).contains("RECONCILE");
    }

    /**
     * The carrier changed its result; after «Принять версию перевозчика» the request has the carrier's result, but the
     * problem and the check still show what differed ({@code docs/specs/conflict-history.md}).
     */
    @Test
    void appliedConflictKeepsBothSides() {
        Conflict conflict = conflict("conflict applied");

        E2eClient.Response resolved = resolve(conflict.problemId(), "APPLY_CARRIER");

        assertThat(resolved.status()).isEqualTo(200);
        JsonNode card = operator.get("/api/requests/" + conflict.requestId()).body();
        assertThat(card.get("result").get("trackingNumber").asString()).isEqualTo(CARRIER_TRACKING);
        JsonNode problem = problem(card, conflict.problemId());
        assertThat(problem.get("currentResult").get("result").get("trackingNumber").asString())
                .isEqualTo(CARRIER_TRACKING);
        assertThat(problem.get("message").get("trackingNumber").asString()).isEqualTo(CARRIER_TRACKING);
        assertThat(problem.get("ourSide").get("result")).as("our result before the decision")
                .isEqualTo(conflict.ours());
        assertBothSidesInCheck(card, conflict);
    }

    @Test
    void keptConflictKeepsBothSides() {
        Conflict conflict = conflict("conflict kept");

        E2eClient.Response resolved = resolve(conflict.problemId(), "KEEP_OURS");

        assertThat(resolved.status()).isEqualTo(200);
        JsonNode card = operator.get("/api/requests/" + conflict.requestId()).body();
        JsonNode problem = problem(card, conflict.problemId());
        assertThat(problem.get("ourSide").get("result")).isEqualTo(conflict.ours());
        assertThat(problem.get("message").get("trackingNumber").asString()).isEqualTo(CARRIER_TRACKING);
        assertThat(problem.get("currentResult").get("result").get("trackingNumber").asString())
                .isNotEqualTo(CARRIER_TRACKING);
        assertBothSidesInCheck(card, conflict);
    }

    @Test
    void initiatorCannotReconcile() {
        String id = create(uniqueCargo("reconcile access"));

        assertThat(initiator.post("/api/requests/" + id + "/reconcile", null, idempotencyKey(newKey())).status())
                .isEqualTo(403);
        assertThat(initiator.get("/api/requests/" + id).body().has("reconciliations")).isFalse();
    }

    /** A finished request whose result the carrier then changed, checked by «Сверить сейчас»: a conflict is open. */
    private Conflict conflict(String what) {
        String id = create(uniqueCargo(what));
        JsonNode finished = Awaits.finalCard(operator, id);
        String operationId = finished.get("operationId").asString();
        assertThat(finished.path("result").path("trackingNumber").asString("")).isNotEqualTo(CARRIER_TRACKING);
        carrierDatabase.changeResult(operationId, CARRIER_TRACKING, new BigDecimal("999.00"));

        E2eClient.Response reconciled = operator.post("/api/requests/" + id + "/reconcile", null,
                idempotencyKey(newKey()));

        assertThat(reconciled.status()).isEqualTo(200);
        assertThat(reconciled.body().get("outcome").asString()).isEqualTo("CONFLICT");
        JsonNode ours = reconciled.body().get("localResult");
        assertThat(ours.get("status").asString()).isEqualTo(finished.get("businessStatus").asString());
        JsonNode card = operator.get("/api/requests/" + id).body();
        JsonNode open = card.get("problems").valueStream()
                .filter(p -> p.get("kind").asString().equals("RECONCILIATION_CONFLICT"))
                .filter(p -> p.get("status").asString().equals("OPEN"))
                .findFirst().orElseThrow();
        return new Conflict(id, open.get("id").asString(), reconciled.body().get("id").asString(), ours);
    }

    private E2eClient.Response resolve(String problemId, String decision) {
        return operator.post("/api/problems/" + problemId + "/resolve",
                Map.of("decision", decision, "comment", "e2e: checked with the carrier"), idempotencyKey(newKey()));
    }

    private static JsonNode problem(JsonNode card, String problemId) {
        return card.get("problems").valueStream().filter(p -> p.get("id").asString().equals(problemId))
                .findFirst().orElseThrow();
    }

    /** The check that found the conflict keeps both sides, whatever was decided after it. */
    private static void assertBothSidesInCheck(JsonNode card, Conflict conflict) {
        JsonNode check = card.get("reconciliations").valueStream()
                .filter(c -> c.get("id").asString().equals(conflict.checkId()))
                .findFirst().orElseThrow();
        assertThat(check.get("localResult")).isEqualTo(conflict.ours());
        assertThat(check.get("carrierResult").get("trackingNumber").asString()).isEqualTo(CARRIER_TRACKING);
        assertThat(check.get("carrierResult").get("price").decimalValue()).isEqualByComparingTo("999.00");
    }

    /**
     * @param ours our result at the check, as {@code localResult}
     */
    private record Conflict(String requestId, String problemId, String checkId, JsonNode ours) {
    }

    private String create(String cargo) {
        Map<String, Object> body = Map.of("cargoDescription", cargo, "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }
}
