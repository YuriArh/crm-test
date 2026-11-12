package com.acme.carrier.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContentHashTest {

    static final UUID ID = UUID.randomUUID();

    @Test
    void equalContentHasEqualHash() {
        assertThat(ContentHash.of(request("Pallets", "12.5", "Main st 1")))
                .isEqualTo(ContentHash.of(request(" Pallets ", "12.50", "Main st 1 ")));
    }

    @Test
    void anyFieldChangesTheHash() {
        String base = ContentHash.of(request("Pallets", "12.5", "Main st 1"));

        assertThat(ContentHash.of(request("Boxes", "12.5", "Main st 1"))).isNotEqualTo(base);
        assertThat(ContentHash.of(request("Pallets", "12.6", "Main st 1"))).isNotEqualTo(base);
        assertThat(ContentHash.of(request("Pallets", "12.5", "Main st 2"))).isNotEqualTo(base);
        assertThat(ContentHash.of(new SubmitOperationRequest(ID, "Pallets", new BigDecimal("12.5"), "Main st 1",
                LocalDate.of(2026, 12, 2)))).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotShift() {
        assertThat(ContentHash.of(request("ab", "1", "c"))).isNotEqualTo(ContentHash.of(request("a", "1", "bc")));
    }

    @Test
    void operationIdIsNotPartOfTheContent() {
        SubmitOperationRequest other = new SubmitOperationRequest(UUID.randomUUID(), "Pallets",
                new BigDecimal("12.5"), "Main st 1", LocalDate.of(2026, 12, 1));

        assertThat(ContentHash.of(other)).isEqualTo(ContentHash.of(request("Pallets", "12.5", "Main st 1")));
    }

    private static SubmitOperationRequest request(String cargo, String weight, String address) {
        return new SubmitOperationRequest(ID, cargo, new BigDecimal(weight), address, LocalDate.of(2026, 12, 1));
    }
}
