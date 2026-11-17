package com.acme.e2e;

import static com.acme.e2e.CarrierFaultsE2ETest.uniqueCargo;
import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import com.acme.e2e.CarrierClient.Faults;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Problems of the exchange on the running stack (docs/specs/operator-problems.md §3): they appear from real faults of
 * the emulator, the system closes what it can, an operator closes the rest.
 */
class ProblemsE2ETest {

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
     * With {@code loseResponse} and {@code delayed} the first send times out: the delivery is ambiguous and the
     * operator sees it; the resend with the same operationId is answered, and the system closes the problem.
     * ({@code loseResponse} alone is not enough: the result comes through the feed before the send times out.)
     */
    @Test
    void lostResponseShowsAndClosesDeliveryUnknown() {
        String cargo = uniqueCargo("problems loseResponse+delayed");
        carrier.setFaults(new Faults(false, true, false, true, false));
        String id = create(cargo);

        JsonNode open = Awaits.card(operator, id, "an open DELIVERY_UNKNOWN",
                card -> problem(card, "DELIVERY_UNKNOWN", "OPEN") != null, Duration.ofSeconds(30));
        assertThat(problem(open, "DELIVERY_UNKNOWN", "OPEN").get("request").get("id").asString()).isEqualTo(id);

        JsonNode closed = Awaits.card(operator, id, "DELIVERY_UNKNOWN closed by the system",
                card -> problem(card, "DELIVERY_UNKNOWN", "RESOLVED") != null, Duration.ofSeconds(30));
        JsonNode resolved = problem(closed, "DELIVERY_UNKNOWN", "RESOLVED");
        assertThat(resolved.get("decision").asString()).isEqualTo("SYSTEM");
        assertThat(resolved.get("resolvedBy").isNull()).isTrue();
        assertThat(closed.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");

        carrier.faultsOff();
        JsonNode operation = carrier.adminOperation(closed.get("operationId").asString());
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
    }

    /** A record broken by {@code malformed} becomes INVALID_MESSAGE of its request; «Разобрано» closes it. */
    @Test
    void malformedRecordBecomesAProblemAndIsAcknowledged() {
        carrier.setFaults(Faults.MALFORMED);
        String id = create(uniqueCargo("problems malformed"));
        Awaits.card(operator, id, "IN_PROGRESS", card -> card.get("businessStatus").asString().equals("IN_PROGRESS"),
                TIMEOUT);
        carrier.faultsOff();

        JsonNode problem = Awaits.until("INVALID_MESSAGE of the request",
                () -> invalidMessageOf(id), found -> found != null, TIMEOUT);
        assertThat(problem.get("message").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(problem.get("actions").get(0).asString()).isEqualTo("ACKNOWLEDGED");
        String problemId = problem.get("id").asString();

        E2eClient.Response one = operator.get("/api/problems/" + problemId);
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.body()).as("one problem is its item of the list").isEqualTo(problem);
        JsonNode row = registryRow(id, "&hasOpenProblems=true");
        assertThat(row).as("among the requests with open problems").isNotNull();
        assertThat(row.get("openProblems").asInt()).isEqualTo(1);

        E2eClient.Response resolved = operator.post("/api/problems/" + problemId + "/resolve",
                Map.of("decision", "ACKNOWLEDGED", "comment", "Сообщил перевозчику о битой цене"),
                idempotencyKey(newKey()));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.body().get("resolvedBy").get("displayName").asString()).isEqualTo("Оператор (Север)");
        assertThat(invalidMessageOf(id)).as("no longer among the open ones").isNull();
        assertThat(registryRow(id, "&hasOpenProblems=true")).as("no longer among requests with problems").isNull();
        assertThat(registryRow(id, "").get("openProblems").asInt()).isZero();
    }

    @Test
    void initiatorHasNoAccessToProblems() {
        String id = create(uniqueCargo("problems access"));

        assertThat(initiator.get("/api/problems").status()).isEqualTo(403);
        assertThat(initiator.post("/api/requests/" + id + "/retry", null, idempotencyKey(newKey())).status())
                .isEqualTo(403);
        assertThat(initiator.post("/api/problems/" + UUID.randomUUID() + "/resolve",
                Map.of("decision", "ACKNOWLEDGED", "comment", "x"), idempotencyKey(newKey())).status())
                .isEqualTo(403);
        JsonNode card = initiator.get("/api/requests/" + id).body();
        assertThat(card.has("problems")).isFalse();
        assertThat(card.get("actions").size()).isZero();
        assertThat(initiator.get("/api/problems/" + UUID.randomUUID()).status()).isEqualTo(403);
        assertThat(initiator.get("/api/requests?hasOpenProblems=true").status()).isEqualTo(403);
        JsonNode rows = initiator.get("/api/requests?size=100").body().get("items");
        for (JsonNode row : rows) {
            assertThat(row.get("openProblems").isNull()).as("initiators never see problem counts").isTrue();
        }
    }

    /** The registry row of the request among the newest 100 matching {@code filter}, or {@code null}. */
    private JsonNode registryRow(String requestId, String filter) {
        E2eClient.Response page = operator.get("/api/requests?size=100&sort=createdAt,desc" + filter);
        assertThat(page.status()).isEqualTo(200);
        for (JsonNode item : page.body().get("items")) {
            if (item.get("id").asString().equals(requestId)) {
                return item;
            }
        }
        return null;
    }

    /** The open INVALID_MESSAGE of the request, or {@code null}. */
    private JsonNode invalidMessageOf(String requestId) {
        E2eClient.Response page = operator.get("/api/problems?kind=INVALID_MESSAGE&size=100");
        assertThat(page.status()).isEqualTo(200);
        for (JsonNode item : page.body().get("items")) {
            JsonNode request = item.get("request");
            if (!request.isNull() && request.get("id").asString().equals(requestId)) {
                return item;
            }
        }
        return null;
    }

    private static JsonNode problem(JsonNode card, String kind, String status) {
        JsonNode problems = card.get("problems");
        if (problems == null) {
            return null;
        }
        for (JsonNode problem : problems) {
            if (problem.get("kind").asString().equals(kind) && problem.get("status").asString().equals(status)) {
                return problem;
            }
        }
        return null;
    }

    private String create(String cargo) {
        Map<String, Object> body = Map.of("cargoDescription", cargo, "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }
}
