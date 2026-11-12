package com.acme.dispatch.request;

import static com.acme.dispatch.common.web.ApiExceptionHandler.IDEMPOTENCY_KEY_HEADER;

import java.net.URI;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.idempotency.IdempotentOutcome;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Create and edit endpoints. Access scope and rights are checked in {@link RequestService}. */
@RestController
@RequestMapping("/api/requests")
class RequestCommandController {

    /** Set on a response that was replayed for a repeated Idempotency-Key. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final RequestService service;

    RequestCommandController(RequestService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<RequestCard> create(@AuthenticationPrincipal CurrentUser user,
                                       @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                       @Valid @RequestBody CreateRequestBody body) {
        // A missing header is reported by ApiExceptionHandler, a blank or too long one by IdempotencyService.
        IdempotentOutcome<RequestCard> outcome = service.create(user, idempotencyKey, body);
        return ResponseEntity.status(outcome.status())
                .location(URI.create("/api/requests/" + outcome.resourceId()))
                .header(REPLAYED_HEADER, Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }

    /** Body {@code {version, assigneeId?, comment?}}; see {@link UpdateRequestBody} for omitted vs {@code null}. */
    @PatchMapping("/{id}")
    RequestCard update(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
                       @RequestBody JsonNode body) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw RequestQueryService.notFound(id);
        }
        return service.update(user, requestId, UpdateRequestBody.parse(body));
    }
}
