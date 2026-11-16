package com.acme.carrier.fault;

import java.math.BigDecimal;
import java.util.List;

import com.acme.carrier.feed.ResultEntry;
import com.acme.carrier.operation.ResultStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fault mode {@code malformed} ({@code docs/specs/carrier-faults.md} §3.5): while it is on, every {@code SUCCEEDED}
 * entry of a feed response carries a price beyond the contract. Only the response is spoiled, at the moment of the
 * read: nothing is stored or remembered, so stored entries, operations and the admin view keep the real price.
 */
@Component
public class MalformedResults {

    /** Beyond the contract's {@code maximum} (9999999999.99) and beyond what a {@code numeric(12, 2)} column holds. */
    public static final BigDecimal BROKEN_PRICE = new BigDecimal("10000000000000.00");

    private static final Logger log = LoggerFactory.getLogger(MalformedResults.class);

    private final FaultModes faults;

    public MalformedResults(FaultModes faults) {
        this.faults = faults;
    }

    /** The entries as they go out: unchanged unless the mode is on. */
    public List<ResultEntry> apply(List<ResultEntry> entries) {
        if (!faults.current().malformed()) {
            return entries;
        }
        return entries.stream().map(MalformedResults::spoil).toList();
    }

    private static ResultEntry spoil(ResultEntry entry) {
        if (entry.status() != ResultStatus.SUCCEEDED) {
            return entry;
        }
        log.warn("Fault malformed: result {} of operation {} goes out with price {} instead of {}", entry.messageId(),
                entry.operationId(), BROKEN_PRICE, entry.price());
        return new ResultEntry(entry.feedSeq(), entry.messageId(), entry.operationId(), entry.sequence(),
                entry.status(), entry.trackingNumber(), BROKEN_PRICE, entry.reason(), entry.occurredAt());
    }
}
