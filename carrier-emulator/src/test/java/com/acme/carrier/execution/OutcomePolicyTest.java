package com.acme.carrier.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import com.acme.carrier.operation.ResultStatus;

import org.junit.jupiter.api.Test;

class OutcomePolicyTest {

    static final UUID ID = UUID.fromString("0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f");

    @Test
    void ordinaryDeliverySucceedsWithTrackingNumberAndPrice() {
        Outcome outcome = OutcomePolicy.decide(ID, new BigDecimal("12.5"), "Main st 1");

        assertThat(outcome.status()).isEqualTo(ResultStatus.SUCCEEDED);
        assertThat(outcome.trackingNumber()).matches("TRK-[0-9A-F]{8}");
        assertThat(outcome.price()).isEqualByComparingTo("612.50");
        assertThat(outcome.price().scale()).isEqualTo(2);
        assertThat(outcome.reason()).isNull();
    }

    @Test
    void weightLimitIsInclusive() {
        assertThat(OutcomePolicy.decide(ID, new BigDecimal("10000"), "a").status()).isEqualTo(ResultStatus.SUCCEEDED);

        Outcome overweight = OutcomePolicy.decide(ID, new BigDecimal("10000.1"), "a");
        assertThat(overweight.status()).isEqualTo(ResultStatus.REJECTED);
        assertThat(overweight.reason()).isEqualTo("Превышен допустимый вес");
        assertThat(overweight.trackingNumber()).isNull();
        assertThat(overweight.price()).isNull();
    }

    @Test
    void addressMarkerRejectsInAnyCase() {
        Outcome outcome = OutcomePolicy.decide(ID, BigDecimal.ONE, "ул. Ленина, ТЕСТ-ОТКАЗ");

        assertThat(outcome.status()).isEqualTo(ResultStatus.REJECTED);
        assertThat(outcome.reason()).isEqualTo("Адрес не обслуживается");
    }

    @Test
    void weightIsCheckedBeforeAddress() {
        assertThat(OutcomePolicy.decide(ID, new BigDecimal("15000"), "тест-отказ").reason())
                .isEqualTo("Превышен допустимый вес");
    }

    @Test
    void trackingNumberIsStablePerOperationAndDiffersBetweenOperations() {
        assertThat(OutcomePolicy.trackingNumber(ID)).isEqualTo(OutcomePolicy.trackingNumber(ID));
        assertThat(OutcomePolicy.trackingNumber(ID)).isNotEqualTo(OutcomePolicy.trackingNumber(UUID.randomUUID()));
    }

    @Test
    void priceIsBasePlusPerKilogram() {
        assertThat(OutcomePolicy.price(new BigDecimal("0.1"))).hasToString("302.50");
        assertThat(OutcomePolicy.price(new BigDecimal("10000"))).hasToString("250300.00");
    }
}
