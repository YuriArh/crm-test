package com.acme.dispatch.exchange.reconcile;

import com.acme.dispatch.exchange.ExchangeProperties;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When background reconciliation runs ({@code docs/specs/reconciliation.md} §2.1): one pass once the application is
 * ready, then a pass every {@code reconcile-interval}, the first one an interval after the start. Only while the
 * exchange is on ({@code app.exchange.enabled}); a manual check works regardless.
 */
@Component
class ReconciliationSchedule {

    private final Reconciler reconciler;
    private final ExchangeProperties properties;

    ReconciliationSchedule(Reconciler reconciler, ExchangeProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (properties.enabled()) {
            reconciler.runOnce(RunType.STARTUP);
        }
    }

    @Scheduled(fixedDelayString = "${app.exchange.reconcile-interval:1m}",
            initialDelayString = "${app.exchange.reconcile-interval:1m}")
    void scheduled() {
        if (properties.enabled()) {
            reconciler.runOnce(RunType.SCHEDULED);
        }
    }
}
