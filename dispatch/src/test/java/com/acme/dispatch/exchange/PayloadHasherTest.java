package com.acme.dispatch.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.acme.dispatch.request.DeliveryContent;

import org.junit.jupiter.api.Test;

class PayloadHasherTest {

    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 20);

    @Test
    void sameContentGivesTheSameHash() {
        assertThat(PayloadHasher.hash(content("Pallets", "12.5", "Main st 1")))
                .isEqualTo(PayloadHasher.hash(content("Pallets", "12.5", "Main st 1")));
    }

    @Test
    void weightScaleDoesNotChangeTheHash() {
        assertThat(PayloadHasher.hash(content("Pallets", "12.50", "Main st 1")))
                .isEqualTo(PayloadHasher.hash(content("Pallets", "12.5", "Main st 1")));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = PayloadHasher.hash(content("Pallets", "12.5", "Main st 1"));

        assertThat(PayloadHasher.hash(content("Boxes", "12.5", "Main st 1"))).isNotEqualTo(base);
        assertThat(PayloadHasher.hash(content("Pallets", "12.6", "Main st 1"))).isNotEqualTo(base);
        assertThat(PayloadHasher.hash(content("Pallets", "12.5", "Main st 2"))).isNotEqualTo(base);
        assertThat(PayloadHasher.hash(new DeliveryContent("Pallets", new BigDecimal("12.5"), "Main st 1",
                DEADLINE.plusDays(1)))).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        assertThat(PayloadHasher.hash(content("ab", "1", "c")))
                .isNotEqualTo(PayloadHasher.hash(content("a", "1", "bc")));
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(PayloadHasher.hash(content("Pallets", "12.5", "Main st 1"))).matches("[0-9a-f]{64}");
    }

    private static DeliveryContent content(String cargo, String weight, String address) {
        return new DeliveryContent(cargo, new BigDecimal(weight), address, DEADLINE);
    }
}
