package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OverduePolicyTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Test
    void deadlineTodayIsNotOverdue() {
        assertThat(OverduePolicy.isOverdue(BusinessStatus.AWAITING, TODAY, TODAY)).isFalse();
    }

    @Test
    void deadlineYesterdayIsOverdueWhileAwaiting() {
        assertThat(OverduePolicy.isOverdue(BusinessStatus.AWAITING, TODAY.minusDays(1), TODAY)).isTrue();
    }

    @Test
    void deadlineYesterdayIsOverdueWhileInProgress() {
        assertThat(OverduePolicy.isOverdue(BusinessStatus.IN_PROGRESS, TODAY.minusDays(1), TODAY)).isTrue();
    }

    @Test
    void futureDeadlineIsNotOverdue() {
        assertThat(OverduePolicy.isOverdue(BusinessStatus.AWAITING, TODAY.plusDays(3), TODAY)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BusinessStatus.class, names = {"SUCCEEDED", "REJECTED"})
    void finalStatusesAreNeverOverdue(BusinessStatus status) {
        assertThat(OverduePolicy.isOverdue(status, TODAY.minusDays(30), TODAY)).isFalse();
    }
}
