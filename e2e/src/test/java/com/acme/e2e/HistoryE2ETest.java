package com.acme.e2e;

import static com.acme.e2e.CarrierFaultsE2ETest.uniqueCargo;
import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.acme.e2e.CarrierClient.Faults;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The history of a request over real HTTP (docs/specs/request-history.md): what happened to it, by whom, and its
 * exchange with the carrier — all of it for an operator of its service, the business part for its initiator. Fault
 * modes are switched off before and after each test.
 */
class HistoryE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration RESULT_TIMEOUT = Duration.ofSeconds(90);
    static final List<String> BUSINESS = List.of("CREATED", "EDITED", "RESULT_CHANGED", "DELIVERY_ACKNOWLEDGED");

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

    @Test
    void historyFollowsTheRequest() {
        String id = create(uniqueCargo("history"));
        JsonNode card = Awaits.finalCard(operator, id, RESULT_TIMEOUT);
        String operatorId = operator.get("/api/auth/me").body().get("id").asString();
        E2eClient.Response edited = operator.patch("/api/requests/" + id, Map.of("version", card.get("version"),
                "assigneeId", operatorId, "comment", "Checked by the operator"));
        assertThat(edited.status()).isEqualTo(200);

        List<JsonNode> all = history(operator, id);
        assertThat(kinds(all)).first().isEqualTo("EDITED");
        assertThat(kinds(all)).last().isEqualTo("CREATED");
        assertThat(kinds(all)).contains("SEND_ATTEMPT", "DELIVERY_ACKNOWLEDGED", "RESULT_RECEIVED", "RESULT_CHANGED");
        JsonNode edit = all.getFirst();
        assertThat(edit.get("actor").get("id").asString()).isEqualTo(operatorId);
        assertThat(edit.get("version").asLong()).isEqualTo(card.get("version").asLong() + 1);
        assertThat(edit.get("changes").valueStream().map(change -> change.get("field").asString()))
                .containsExactly("comment", "assignee");
        JsonNode finalResult = all.stream().filter(entry -> entry.get("kind").asString().equals("RESULT_CHANGED"))
                .findFirst().orElseThrow();
        assertThat(finalResult.get("actor").isNull()).as("the carrier's result, by the system").isTrue();
        assertThat(finalResult.get("version").asLong()).isEqualTo(card.get("version").asLong());

        List<JsonNode> business = history(initiator, id);
        assertThat(kinds(business)).isSubsetOf(BUSINESS).contains("CREATED", "EDITED", "RESULT_CHANGED");
        assertThat(business).hasSize((int) all.stream()
                .filter(entry -> BUSINESS.contains(entry.get("kind").asString())).count());
    }

    @Test
    void retryIsInHistory() {
        carrier.setFaults(Faults.LOSE_RESPONSE);
        String id = create(uniqueCargo("history retry"));
        String operationId = operator.get("/api/requests/" + id).body().get("operationId").asString();
        // Once the carrier has the first send, it becomes unavailable: the answer is lost, the resends get 503,
        // and the delivery stays UNKNOWN long enough for the operator to retry.
        Awaits.until("the carrier has the operation", () -> carrier.findAdminOperation(operationId),
                Objects::nonNull, Duration.ofSeconds(20));
        carrier.setFaults(new Faults(true, true, false, false, false));
        Awaits.card(operator, id, "UNKNOWN", card -> card.get("deliveryStatus").asString().equals("UNKNOWN"),
                Duration.ofSeconds(30));

        E2eClient.Response retried = operator.post("/api/requests/" + id + "/retry", null,
                idempotencyKey(newKey()));
        assertThat(retried.status()).isEqualTo(200);
        carrier.faultsOff();
        Awaits.finalCard(operator, id, RESULT_TIMEOUT);

        JsonNode retry = history(operator, id).stream()
                .filter(entry -> entry.get("kind").asString().equals("DELIVERY_RETRIED"))
                .findFirst().orElseThrow();
        assertThat(retry.get("actor").get("displayName").asString()).isEqualTo("Оператор (Север)");
        assertThat(retry.get("changes").get(0).get("from").asString()).isEqualTo("UNKNOWN");
        assertThat(kinds(history(initiator, id))).doesNotContain("DELIVERY_RETRIED", "SEND_ATTEMPT");
    }

    @Test
    void initiatorCannotReadForeignHistory() {
        String id = create(uniqueCargo("history foreign"));

        assertThat(E2eClient.signedInAs("south.initiator").get("/api/requests/" + id + "/history").status())
                .isEqualTo(404);
    }

    /** The whole history, newest first, page by page. */
    private static List<JsonNode> history(E2eClient client, String id) {
        List<JsonNode> entries = new ArrayList<>();
        String cursor = null;
        do {
            E2eClient.Response page = client.get("/api/requests/" + id + "/history?limit=20"
                    + (cursor == null ? "" : "&before=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            page.body().get("items").forEach(entries::add);
            JsonNode next = page.body().get("nextCursor");
            cursor = next.isNull() ? null : next.asString();
        } while (cursor != null);
        return entries;
    }

    private static List<String> kinds(List<JsonNode> entries) {
        return entries.stream().map(entry -> entry.get("kind").asString()).toList();
    }

    private String create(String cargo) {
        Map<String, Object> body = Map.of("cargoDescription", cargo, "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }
}
