package com.acme.dispatch.history;

import java.util.List;

/** One page of a request's history, newest first; {@code nextCursor} is {@code null} on the last page. */
public record HistoryPage(List<HistoryEntry> items, String nextCursor) {
}
