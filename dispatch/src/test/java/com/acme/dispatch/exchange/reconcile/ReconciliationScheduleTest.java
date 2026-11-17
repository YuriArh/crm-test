package com.acme.dispatch.exchange.reconcile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import com.acme.dispatch.exchange.ExchangeProperties;

import org.junit.jupiter.api.Test;

/** Background reconciliation runs only while the exchange is on ({@code reconciliation.md} §2.1). */
class ReconciliationScheduleTest {

    final Reconciler reconciler = mock(Reconciler.class);

    @Test
    void startupRunsOnceWhenEnabled() {
        new ReconciliationSchedule(reconciler, properties(true)).onStartup();

        verify(reconciler).runOnce(RunType.STARTUP);
    }

    @Test
    void scheduledRunsWhenEnabled() {
        new ReconciliationSchedule(reconciler, properties(true)).scheduled();

        verify(reconciler).runOnce(RunType.SCHEDULED);
        verify(reconciler, never()).runOnce(RunType.STARTUP);
    }

    @Test
    void nothingRunsWhenDisabled() {
        ReconciliationSchedule schedule = new ReconciliationSchedule(reconciler, properties(false));

        schedule.onStartup();
        schedule.scheduled();

        verifyNoInteractions(reconciler);
    }

    private static ExchangeProperties properties(boolean enabled) {
        return new ExchangeProperties(enabled, 20, Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofMinutes(5),
                8, 100, Duration.ofSeconds(30), 5, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofHours(1), 100);
    }
}
