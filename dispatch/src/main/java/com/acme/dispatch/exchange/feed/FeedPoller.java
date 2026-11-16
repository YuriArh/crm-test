package com.acme.dispatch.exchange.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.CarrierUnavailableException;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.inbox.InboxProcessor;
import com.acme.dispatch.exchange.inbox.InboxSource;
import com.acme.dispatch.exchange.inbox.ResultInbox;

import tools.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls results from the carrier's feed and has them processed ({@code docs/dispatch-design.md} §5,
 * {@code docs/specs/feed-bad-records.md}). One pass has two steps:
 * <ol>
 *   <li>receiving: a page is read outside any transaction; then, in one transaction, the cursor row is locked, every
 *       entry is stored by {@link ResultInbox} without looking at its content, and the cursor moves past the page.
 *       A restart therefore neither loses nor receives a page twice. A full page is followed by the next one at once.
 *       A page whose envelope ({@link FeedEnvelope}) is broken is not received at all. While the carrier is
 *       unavailable or its feed is broken, reading pauses 1 → 2 → … → 30 s;</li>
 *   <li>processing: {@link InboxProcessor} decides every due record, each in its own transaction — also while
 *       reading pauses.</li>
 * </ol>
 */
@Component
public class FeedPoller {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);
    private static final Duration BASE_PAUSE = Duration.ofSeconds(1);

    private final CarrierClient carrier;
    private final ResultInbox inbox;
    private final InboxProcessor processor;
    private final FeedCursorDao cursor;
    private final BusinessCalendar calendar;
    private final TransactionTemplate transaction;
    private final ExchangeProperties properties;

    private int failuresInARow;
    private Instant pausedUntil = Instant.MIN;

    public FeedPoller(CarrierClient carrier, ResultInbox inbox, InboxProcessor processor, FeedCursorDao cursor,
                      BusinessCalendar calendar, TransactionTemplate transaction, ExchangeProperties properties) {
        this.carrier = carrier;
        this.inbox = inbox;
        this.processor = processor;
        this.cursor = cursor;
        this.calendar = calendar;
        this.transaction = transaction;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.exchange.feed-interval-ms:1000}")
    void tick() {
        if (properties.enabled()) {
            runOnce();
        }
    }

    /** One pass: receive what the feed has, then process every due record. Returns the number of entries received. */
    public synchronized int runOnce() {
        int received = calendar.now().isBefore(pausedUntil) ? 0 : receiveAvailable();
        processor.processDue();
        return received;
    }

    /** Reads pages until the feed is drained, unavailable or broken. Returns the number of entries received. */
    private int receiveAvailable() {
        int handled = 0;
        while (true) {
            long after = cursor.read();
            List<JsonNode> page;
            try {
                page = carrier.results(after, properties.feedPageSize());
            } catch (CarrierUnavailableException e) {
                if (pause() == 1) {
                    log.warn("Carrier result feed is unavailable: {}", e.getMessage());
                }
                return handled;
            }
            if (page.isEmpty()) {
                resume();
                return handled;
            }
            Optional<String> violation = FeedEnvelope.violation(after, page);
            if (violation.isPresent()) {
                // Without positions there is no place to continue from; skipping could lose results.
                pause();
                log.error("Feed protocol violated in the page after {}: {}; nothing is received, retrying after a pause",
                        after, violation.get());
                return handled;
            }
            resume();
            Integer received = transaction.execute(status -> receive(after, page));
            if (received == null || received == 0) {
                return handled;
            }
            handled += received;
            if (page.size() < properties.feedPageSize()) {
                return handled;
            }
        }
    }

    private int receive(long after, List<JsonNode> page) {
        if (cursor.lock() != after) {
            // Another instance received this page meanwhile; read again on the next pass.
            return 0;
        }
        for (JsonNode entry : page) {
            inbox.receive(entry, InboxSource.FEED);
        }
        cursor.move(FeedEnvelope.lastFeedSeq(page), calendar.now());
        return page.size();
    }

    /** Starts or extends the pause after a failed read; returns the number of failed reads in a row. */
    private int pause() {
        failuresInARow++;
        long seconds = Math.min(BASE_PAUSE.toSeconds() << Math.min(failuresInARow - 1, 10),
                properties.feedMaxBackoff().toSeconds());
        pausedUntil = calendar.now().plusSeconds(seconds);
        return failuresInARow;
    }

    private void resume() {
        if (failuresInARow > 0) {
            log.info("Carrier result feed is readable again after {} failed read(s)", failuresInARow);
        }
        failuresInARow = 0;
        pausedUntil = Instant.MIN;
    }
}
