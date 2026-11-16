package com.acme.dispatch.exchange.feed;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * The envelope of a feed page: the position ({@code feedSeq}) of every record, which the cursor follows
 * ({@code docs/specs/feed-bad-records.md} §3.1). A page is accepted only if every element is an object with an
 * integer position in {@code 1 … Long.MAX_VALUE}, the first is after the cursor and the rest strictly increase
 * (gaps are allowed). The content of the records is not looked at: that is the job of processing.
 */
final class FeedEnvelope {

    private FeedEnvelope() {
    }

    /** Why the page cannot be accepted after {@code after}, or empty if it can. */
    static Optional<String> violation(long after, List<JsonNode> page) {
        long previous = after;
        for (int i = 0; i < page.size(); i++) {
            JsonNode element = page.get(i);
            String where = "element " + (i + 1);
            if (!element.isObject()) {
                return Optional.of(where + " is not an object");
            }
            JsonNode position = element.path("feedSeq");
            if (!position.isIntegralNumber() || !position.canConvertToLong() || position.asLong() < 1) {
                return Optional.of(where + " has no feedSeq in 1.." + Long.MAX_VALUE);
            }
            long feedSeq = position.asLong();
            if (feedSeq <= previous) {
                return Optional.of(where + " has feedSeq " + feedSeq + ", not after " + previous);
            }
            previous = feedSeq;
        }
        return Optional.empty();
    }

    /** Position of the last record of a page that has no {@link #violation}. */
    static long lastFeedSeq(List<JsonNode> page) {
        return page.getLast().get("feedSeq").asLong();
    }
}
