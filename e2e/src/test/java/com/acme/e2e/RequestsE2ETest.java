package com.acme.e2e;

import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The main requests scenario over real HTTP against the running stack. Creates its own data and
 * never assumes an empty database.
 */
class RequestsE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();

    final E2eClient northInitiator = E2eClient.signedInAs("north.initiator");
    final E2eClient northOperator = E2eClient.signedInAs("north.operator");
    final E2eClient southInitiator = E2eClient.signedInAs("south.initiator");

    @Test
    void createSeeAssignAndKeepOthersOut() {
        String key = newKey();
        Map<String, Object> body = newRequest("E2E pallets " + key);

        // Initiator creates a request: accepted locally and queued for the carrier.
        E2eClient.Response created = northInitiator.post("/api/requests", body, idempotencyKey(key));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("deliveryStatus").asString()).isEqualTo("PENDING");
        assertThat(created.body().get("businessStatus").asString()).isEqualTo("AWAITING");
        String id = created.body().get("id").asString();
        assertThat(created.header("Location")).isEqualTo("/api/requests/" + id);

        // A repeated click with the same key does not create a second request.
        E2eClient.Response repeated = northInitiator.post("/api/requests", body, idempotencyKey(key));
        assertThat(repeated.status()).isEqualTo(201);
        assertThat(repeated.body().get("id").asString()).isEqualTo(id);
        assertThat(repeated.header("Idempotent-Replayed")).isEqualTo("true");

        // It is in the initiator's registry filtered by business status.
        assertThat(idsIn(northInitiator.get("/api/requests?businessStatus=AWAITING&size=100"))).contains(id);

        // The operator of the same service sees it and assigns himself.
        assertThat(idsIn(northOperator.get("/api/requests?size=100"))).contains(id);
        E2eClient.Response operatorCard = northOperator.get("/api/requests/" + id);
        assertThat(operatorCard.status()).isEqualTo(200);
        assertThat(operatorCard.body().get("exchange")).isNotNull();
        String operatorId = northOperator.get("/api/auth/me").body().get("id").asString();
        E2eClient.Response assigned = northOperator.patch("/api/requests/" + id,
                Map.of("version", 0, "assigneeId", operatorId));
        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.body().get("assignee").get("id").asString()).isEqualTo(operatorId);
        assertThat(assigned.body().get("version").asLong()).isEqualTo(1);

        // An initiator of another service neither sees nor opens it.
        assertThat(southInitiator.get("/api/requests/" + id).status()).isEqualTo(404);
        assertThat(idsIn(southInitiator.get("/api/requests?size=100"))).doesNotContain(id);

        // Editing with an outdated version is a conflict, not a silent overwrite.
        E2eClient.Response stale = northInitiator.patch("/api/requests/" + id,
                Map.of("version", 0, "comment", "outdated edit"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(stale.body().get("current").get("version").asLong()).isEqualTo(1);

        // Sending the unchanged assignee is a no-op, but actually changing it is for operators only.
        E2eClient.Response sameAssignee = northInitiator.patch("/api/requests/" + id,
                Map.of("version", 1, "assigneeId", operatorId));
        assertThat(sameAssignee.status()).isEqualTo(200);
        Map<String, Object> unassign = new java.util.HashMap<>();
        unassign.put("version", 1);
        unassign.put("assigneeId", null);
        E2eClient.Response initiatorUnassign = northInitiator.patch("/api/requests/" + id, unassign);
        assertThat(initiatorUnassign.status()).isEqualTo(403);
        assertThat(initiatorUnassign.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void createRequiresAnIdempotencyKey() {
        E2eClient.Response response = northInitiator.post("/api/requests", newRequest("no key"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void operatorCannotCreate() {
        E2eClient.Response response = northOperator.post("/api/requests", newRequest("operator"),
                idempotencyKey(newKey()));

        assertThat(response.status()).isEqualTo(403);
    }

    private static Map<String, Object> newRequest(String cargo) {
        return Map.of("cargoDescription", cargo, "weightKg", 12.5, "deliveryAddress", "Main st 1",
                "deadline", DEADLINE);
    }

    private static List<String> idsIn(E2eClient.Response page) {
        assertThat(page.status()).isEqualTo(200);
        JsonNode items = page.body().get("items");
        return StreamSupport.stream(items.spliterator(), false).map(item -> item.get("id").asString()).toList();
    }
}
