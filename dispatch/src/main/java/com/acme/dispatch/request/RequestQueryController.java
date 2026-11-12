package com.acme.dispatch.request;

import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.PageResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Registry and card endpoints. The access scope comes from the session user, never from parameters. */
@RestController
@RequestMapping("/api/requests")
class RequestQueryController {

    private final RequestQueryService service;

    RequestQueryController(RequestQueryService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<RequestListItem> list(@RequestParam(required = false) String businessStatus,
                                       @RequestParam(required = false) String deliveryStatus,
                                       @RequestParam(required = false) String overdue,
                                       @RequestParam(required = false) String page,
                                       @RequestParam(required = false) String size,
                                       @RequestParam(required = false) String sort,
                                       @AuthenticationPrincipal CurrentUser user) {
        RequestListQuery query = RequestListQuery.parse(businessStatus, deliveryStatus, overdue, page, size, sort);
        return service.list(query, user);
    }

    /** A malformed id cannot name an existing request, so it gets the same 404 as a missing one. */
    @GetMapping("/{id}")
    RequestCard card(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw RequestQueryService.notFound(id);
        }
        return service.getCard(requestId, user);
    }
}
