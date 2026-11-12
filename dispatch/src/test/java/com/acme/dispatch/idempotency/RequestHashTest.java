package com.acme.dispatch.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RequestHashTest {

    record CargoAb(String a, BigDecimal b, LocalDate deadline, Map<String, Object> extra) {
    }

    /** Same properties as {@link CargoAb}, declared in a different order. */
    record CargoBa(Map<String, Object> extra, LocalDate deadline, BigDecimal b, String a) {
    }

    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 20);

    @Test
    void propertyAndMapOrderDoNotChangeTheHash() {
        Map<String, Object> extra1 = new LinkedHashMap<>();
        extra1.put("x", 1);
        extra1.put("y", List.of("p", "q"));
        Map<String, Object> extra2 = new LinkedHashMap<>();
        extra2.put("y", List.of("p", "q"));
        extra2.put("x", 1);

        String first = IdempotencyService.requestHash(new CargoAb("Pallets", new BigDecimal("12.5"), DEADLINE, extra1));
        String second = IdempotencyService.requestHash(new CargoBa(extra2, DEADLINE, new BigDecimal("12.5"), "Pallets"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void jsonTreeFieldOrderDoesNotChangeTheHash() {
        JsonMapper mapper = JsonMapper.builder().build();
        Object first = mapper.readTree("{\"a\":\"Pallets\",\"nested\":{\"k\":1,\"j\":2}}");
        Object second = mapper.readTree("{\"nested\":{\"j\":2,\"k\":1},\"a\":\"Pallets\"}");

        assertThat(IdempotencyService.requestHash(first)).isEqualTo(IdempotencyService.requestHash(second));
    }

    @Test
    void differentValueGivesDifferentHash() {
        String base = IdempotencyService.requestHash(new CargoAb("Pallets", new BigDecimal("12.5"), DEADLINE, Map.of()));

        assertThat(IdempotencyService.requestHash(new CargoAb("Boxes", new BigDecimal("12.5"), DEADLINE, Map.of())))
                .isNotEqualTo(base);
        assertThat(IdempotencyService.requestHash(new CargoAb("Pallets", new BigDecimal("13"), DEADLINE, Map.of())))
                .isNotEqualTo(base);
        assertThat(IdempotencyService.requestHash(
                new CargoAb("Pallets", new BigDecimal("12.5"), DEADLINE.plusDays(1), Map.of())))
                .isNotEqualTo(base);
        assertThat(IdempotencyService.requestHash(
                new CargoAb("Pallets", new BigDecimal("12.5"), DEADLINE, Map.of("x", 1))))
                .isNotEqualTo(base);
    }

    @Test
    void hashIs64LowercaseHexChars() {
        assertThat(IdempotencyService.requestHash(Map.of("a", 1))).matches("[0-9a-f]{64}");
    }
}
