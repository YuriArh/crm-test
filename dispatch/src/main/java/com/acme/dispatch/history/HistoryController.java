package com.acme.dispatch.history;

import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.ApiException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/requests/{id}/history}: the access scope comes from the session user, as for the card. */
@RestController
class HistoryController {

    private final HistoryService service;

    HistoryController(HistoryService service) {
        this.service = service;
    }

    /** A malformed id cannot name an existing request, so it gets the same 404 as a missing one. */
    @GetMapping("/api/requests/{id}/history")
    HistoryPage history(@PathVariable String id, @RequestParam(required = false) String before,
                        @RequestParam(required = false) String limit,
                        @AuthenticationPrincipal CurrentUser user) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Request " + id + " not found");
        }
        int size = HistoryRules.limit(limit);
        HistoryCursor cursor = before == null || before.isBlank() ? null : HistoryCursor.parse(before);
        return service.page(requestId, user, cursor, size);
    }
}
