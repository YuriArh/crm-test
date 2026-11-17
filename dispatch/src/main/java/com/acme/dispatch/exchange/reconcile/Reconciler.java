package com.acme.dispatch.exchange.reconcile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.CarrierUnavailableException;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.inbox.InboxDao;
import com.acme.dispatch.exchange.inbox.InboxProcessor;
import com.acme.dispatch.exchange.inbox.InboxSource;
import com.acme.dispatch.exchange.inbox.ResultInbox;
import com.acme.dispatch.exchange.inbox.ResultMessage;
import com.acme.dispatch.exchange.reconcile.ReconciliationDao.Row;
import com.acme.dispatch.exchange.reconcile.ReconciliationDao.State;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.CarrierAnswer;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Local;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Previous;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Signature;
import com.acme.dispatch.problem.ProblemService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation with the carrier ({@code docs/specs/reconciliation.md}): asks the carrier about one operation,
 * compares its answer with our state and acts on the outcome. The decision is taken under the operation's row lock on
 * our state as it is after the lock, so a result the feed applied meanwhile is seen; the carrier's state only moves
 * forward, so only ours can be stale.
 */
@Service
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final CarrierClient carrier;
    private final ReconciliationDao dao;
    private final ProblemService problems;
    private final TransactionTemplate transaction;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;
    private final ExchangeProperties properties;
    private final ResultInbox resultInbox;
    private final InboxDao inbox;
    private final InboxProcessor processor;

    public Reconciler(CarrierClient carrier, ReconciliationDao dao, ProblemService problems,
                      TransactionTemplate transaction, BusinessCalendar calendar, IdGenerator ids,
                      ExchangeProperties properties, ResultInbox resultInbox, InboxDao inbox,
                      InboxProcessor processor) {
        this.carrier = carrier;
        this.dao = dao;
        this.problems = problems;
        this.transaction = transaction;
        this.calendar = calendar;
        this.ids = ids;
        this.properties = properties;
        this.resultInbox = resultInbox;
        this.inbox = inbox;
        this.processor = processor;
    }

    /**
     * Checks one request in the background way: the carrier is asked outside any transaction, then the outcome is
     * decided and recorded in a transaction of its own.
     */
    public Checked check(UUID requestId, RunType runType, UUID performedBy) {
        UUID operationId = dao.operationOf(requestId)
                .orElseThrow(() -> new IllegalStateException("Request " + requestId + " has no operation"));
        Asked asked = ask(operationId);
        return transaction.execute(status -> record(requestId, asked, runType, performedBy));
    }

    private Asked ask(UUID operationId) {
        try {
            return new Asked(ReconciliationRules.answer(carrier.lookup(operationId)), false);
        } catch (CarrierUnavailableException e) {
            return new Asked(new CarrierAnswer.Unavailable(e.getMessage()), true);
        }
    }

    /** Decides and records a check; runs in the caller's transaction. */
    private Checked record(UUID requestId, Asked asked, RunType runType, UUID performedBy) {
        State state = dao.lock(requestId)
                .orElseThrow(() -> new IllegalStateException("Request " + requestId + " has no operation"));
        UUID operationId = state.operationId();
        Instant now = calendar.now();
        CarrierAnswer answer = asked.answer();
        Local local = new Local(state.delivery(), state.localResult());
        ReconciliationOutcome outcome = ReconciliationRules.decide(local, answer);
        String details = ReconciliationRules.details(outcome, local, answer);
        ResultMessage carrierResult = answer instanceof CarrierAnswer.Known known ? known.result() : null;
        String carrierState = switch (answer) {
            case CarrierAnswer.Known known -> known.state();
            case CarrierAnswer.NotFound notFound -> "NOT_FOUND";
            default -> null;
        };
        Signature signature = new Signature(outcome, carrierState,
                carrierResult == null ? null : carrierResult.status(), state.delivery());
        Previous previous = state.reconciledAt() == null ? null : dao.lastSignature(requestId)
                .map(last -> new Previous(state.reconciledAt(), state.nextReconcileAt(), last))
                .orElse(null);

        boolean knows = ReconciliationRules.carrierKnows(answer);
        boolean acknowledged = knows && dao.acknowledge(requestId, now);
        if (acknowledged) {
            log.info("Reconciliation confirmed delivery of operation {} (was {})", operationId, state.delivery());
            problems.onDeliveryStatus(requestId, operationId, DeliveryStatus.ACKNOWLEDGED, null, null, now);
        }
        if (knows) {
            problems.onCarrierKnowsOperation(requestId, operationId, now);
        }

        UUID inboxId = null;
        if (outcome == ReconciliationOutcome.EXTERNAL_AHEAD) {
            TakenOver taken = takeOver(carrierResult);
            inboxId = taken.inboxId();
            details = details + taken.note();
            log.info("Reconciliation EXTERNAL_AHEAD for operation {}: result {} taken over, record {}", operationId,
                    carrierResult.messageId(), taken.note().isEmpty() ? "new" : "existing");
        }

        UUID id = ids.newId();
        dao.insert(new Row(id, requestId, operationId, now, runType, performedBy, outcome, acknowledged,
                state.delivery(), state.localResult(), carrierState, carrierResult, answer.raw(), details, inboxId));
        act(outcome, requestId, operationId, state.delivery(), details, now);
        dao.schedule(requestId, now, ReconciliationRules.nextCheck(previous, signature, now,
                properties.reconcilePause(), properties.reconcilePauseMax(), asked.carrierUnavailable()));
        return new Checked(id, outcome, asked.carrierUnavailable());
    }

    /**
     * The carrier's result goes through the single result handler, like a feed entry: received as a record of
     * reconciliation and processed now, in this transaction. A record of the same message from reconciliation is
     * reused (the unique pair source + messageId); if it is held or decided already, it is left as it is.
     */
    private TakenOver takeOver(ResultMessage result) {
        Optional<UUID> received = resultInbox.receive(result.raw(), InboxSource.RECONCILIATION);
        UUID inboxId = received.orElseGet(() -> inbox.findId(InboxSource.RECONCILIATION, result.messageId())
                .orElseThrow(() -> new IllegalStateException("Record of " + result.messageId() + " vanished")));
        processor.process(inboxId);
        String note = received.isPresent() ? ""
                : "; result already received by reconciliation, record is " + inbox.statusOf(inboxId);
        return new TakenOver(inboxId, note);
    }

    private void act(ReconciliationOutcome outcome, UUID requestId, UUID operationId, DeliveryStatus delivery,
                     String details, Instant now) {
        switch (outcome) {
            case EXTERNAL_MISSING -> {
                log.warn("Reconciliation EXTERNAL_MISSING for operation {}: {}", operationId, details);
                if (delivery == DeliveryStatus.UNKNOWN) {
                    dao.sendNow(requestId, now);
                } else if (delivery == DeliveryStatus.ACKNOWLEDGED) {
                    problems.onExternalMissing(requestId, operationId, details, now);
                }
            }
            case CONFLICT -> log.warn("Reconciliation CONFLICT for operation {}: {}", operationId, details);
            case MATCH, EXTERNAL_AHEAD, ERROR -> {
            }
        }
    }

    /**
     * A recorded check.
     *
     * @param carrierUnavailable whether the carrier could not be asked (the background pass stops)
     */
    public record Checked(UUID reconciliationId, ReconciliationOutcome outcome, boolean carrierUnavailable) {
    }

    private record Asked(CarrierAnswer answer, boolean carrierUnavailable) {
    }

    private record TakenOver(UUID inboxId, String note) {
    }
}
