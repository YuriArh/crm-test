package com.acme.e2e;

import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/**
 * The whole exchange over the running stack: dispatch sends a request to carrier-emulator, the emulator
 * executes it, dispatch reads the result from the feed and shows it on the card.
 */
class ExchangeE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();

    final E2eClient initiator = E2eClient.signedInAs("north.initiator");
    final E2eClient operator = E2eClient.signedInAs("north.operator");
    final CarrierClient carrier = new CarrierClient();

    @Test
    void requestIsDeliveredExecutedOnceAndGetsItsResult() {
        String id = create(12.5, "Main st 1");

        JsonNode card = Awaits.finalCard(initiator, id);

        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(card.get("result").get("trackingNumber").asString()).matches("TRK-[0-9A-F]{8}");
        assertThat(card.get("result").get("price").decimalValue()).isEqualByComparingTo("612.50");
        assertThat(card.get("result").get("resultAt").isNull()).isFalse();

        // The carrier's own state agrees, and the operation was executed exactly once.
        String operationId = card.get("operationId").asString();
        JsonNode carrierState = carrier.get("/operations/" + operationId).body();
        assertThat(carrierState.get("result").get("trackingNumber").asString())
                .isEqualTo(card.get("result").get("trackingNumber").asString());
        JsonNode counters = carrier.get("/admin/operations?operationId=" + operationId).body().get("items").get(0);
        assertThat(counters.get("executionCount").asInt()).isEqualTo(1);

        // The operator sees how it was delivered.
        JsonNode exchange = operator.get("/api/requests/" + id).body().get("exchange");
        assertThat(exchange.get("attempts").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(exchange.get("acknowledgedVia").asString()).isIn("RESPONSE", "RESULT");
    }

    @Test
    void overweightRequestIsRejectedByTheCarrier() {
        String id = create(15000, "Main st 2");

        JsonNode card = Awaits.finalCard(initiator, id);

        assertThat(card.get("businessStatus").asString()).isEqualTo("REJECTED");
        assertThat(card.get("result").get("rejectionReason").asString()).isEqualTo("Превышен допустимый вес");
        assertThat(card.get("result").get("trackingNumber").isNull()).isTrue();
    }

    private String create(Number weight, String address) {
        Map<String, Object> body = Map.of("cargoDescription", "E2E exchange", "weightKg", weight,
                "deliveryAddress", address, "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }
}
