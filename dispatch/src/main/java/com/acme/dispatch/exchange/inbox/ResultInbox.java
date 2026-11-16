package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.config.IdGenerator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives carrier results, whatever transport delivered them ({@code docs/dispatch-design.md} §5,
 * {@code docs/specs/feed-bad-records.md} §3). Receiving stores the raw record as text with status {@code RECEIVED}
 * and does not look at its content, so no record can make it fail; {@link InboxProcessor} decides each record
 * afterwards. A repeated {@code messageId} creates no row: {@code duplicate_count + 1}.
 */
@Service
public class ResultInbox {

    static final int MAX_MESSAGE_ID_LENGTH = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InboxDao inbox;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;

    public ResultInbox(InboxDao inbox, BusinessCalendar calendar, IdGenerator ids) {
        this.inbox = inbox;
        this.calendar = calendar;
        this.ids = ids;
    }

    /**
     * Stores a record for processing. Must run inside the caller's transaction, together with moving the feed
     * cursor.
     *
     * @return the new inbox row, or empty if the message was already received (a duplicate)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> receive(JsonNode raw, InboxSource source) {
        Instant now = calendar.now();
        String messageId = cleanMessageId(raw);
        if (messageId != null && inbox.countDuplicate(messageId, now)) {
            return Optional.empty();
        }
        UUID id = ids.newId();
        if (!inbox.insertReceived(id, messageId, feedSeq(raw), JSON.writeValueAsString(raw), source, now)) {
            inbox.countDuplicate(messageId, now);
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /**
     * The message id if it can be stored and compared as is: 1–100 characters without control characters (the
     * database refuses NUL in any text column). Otherwise the record is stored without it and processing makes it
     * {@code INVALID}.
     */
    static String cleanMessageId(JsonNode raw) {
        JsonNode value = raw.path("messageId");
        if (!value.isString()) {
            return null;
        }
        String messageId = value.asString();
        boolean clean = !messageId.isEmpty() && messageId.length() <= MAX_MESSAGE_ID_LENGTH
                && SafeText.isPrintable(messageId);
        return clean ? messageId : null;
    }

    /** The feed position; the feed poller has already checked the envelope. Records from elsewhere have none. */
    private static Long feedSeq(JsonNode raw) {
        JsonNode value = raw.path("feedSeq");
        return value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }
}
