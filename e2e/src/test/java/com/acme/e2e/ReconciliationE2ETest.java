package com.acme.e2e;

import static com.acme.e2e.CarrierClient.DELAYED_RESULT_DELAY;
import static com.acme.e2e.CarrierFaultsE2ETest.uniqueCargo;
import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

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
 * the carrier has not published in its feed yet, finds nothing to fix on a finished request, and is an operator's
 * action only.
 */
class ReconciliationE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration TIMEOUT = Duration.ofSeconds(90);

    final CarrierClient carrier = new CarrierClient();
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

    @Test
    void initiatorCannotReconcile() {
        String id = create(uniqueCargo("reconcile access"));

        assertThat(initiator.post("/api/requests/" + id + "/reconcile", null, idempotencyKey(newKey())).status())
                .isEqualTo(403);
        assertThat(initiator.get("/api/requests/" + id).body().has("reconciliations")).isFalse();
    }

    private String create(String cargo) {
        Map<String, Object> body = Map.of("cargoDescription", cargo, "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }
}
