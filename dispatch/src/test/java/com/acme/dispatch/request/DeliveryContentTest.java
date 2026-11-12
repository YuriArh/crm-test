package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeliveryContentTest {

    @ParameterizedTest
    @CsvSource({
            "12.50, 12.5",
            "12.5, 12.5",
            "20000, 20000",
            "20000.0, 20000",
            "100.00, 100",
            "0.1, 0.1",
            "10, 10"
    })
    void canonicalWeightIsPlainWithoutTrailingZeros(String input, String expected) {
        BigDecimal canonical = DeliveryContent.canonicalWeight(new BigDecimal(input));

        assertThat(canonical.toString()).isEqualTo(expected);
        assertThat(canonical.scale()).isNotNegative();
    }

    @org.junit.jupiter.api.Test
    void nullStaysNull() {
        assertThat(DeliveryContent.canonicalWeight(null)).isNull();
    }
}
