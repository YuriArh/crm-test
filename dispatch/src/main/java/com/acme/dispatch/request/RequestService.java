package com.acme.dispatch.request;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.auth.Role;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.exchange.PayloadHasher;
import com.acme.dispatch.idempotency.IdempotencyService;
import com.acme.dispatch.idempotency.IdempotentOutcome;
import com.acme.dispatch.idempotency.IdempotentResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Write side of delivery requests: create (with Idempotency-Key) and edit comment / assignee. */
@Service
public class RequestService {

    static final String CREATE_OPERATION = "CREATE_REQUEST";

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    private final DeliveryRequestRepository requests;
    private final DispatchOperationDao operations;
    private final IdempotencyService idempotency;
    private final RequestQueryService queries;
    private final AppUserRepository users;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;

    public RequestService(DeliveryRequestRepository requests, DispatchOperationDao operations,
                          IdempotencyService idempotency, RequestQueryService queries, AppUserRepository users,
                          BusinessCalendar calendar, IdGenerator ids) {
        this.requests = requests;
        this.operations = operations;
        this.idempotency = idempotency;
        this.queries = queries;
        this.users = users;
        this.calendar = calendar;
        this.ids = ids;
    }

    /**
     * Accepts a request locally (D01, D-NF01): the request and its dispatch operation ({@code PENDING},
     * new immutable {@code operationId}) are written in one transaction together with the idempotency
     * key, so a repeated create returns the first result instead of a second delivery (NF04).
     */
    @Transactional
    public IdempotentOutcome<RequestCard> create(CurrentUser user, String idempotencyKey, CreateRequestBody body) {
        if (!RequestPermissions.canCreate(user)) {
            throw ApiException.forbidden("Only initiators create delivery requests");
        }
        // The body already holds the canonical weight, so 12.5 and 12.50 hash the same.
        DeliveryContent content = body.toContent();
        IdempotentOutcome<RequestCard> outcome = idempotency.execute(user.id(), idempotencyKey, CREATE_OPERATION,
                content, RequestCard.class, () -> createNew(user, content));
        if (!outcome.replayed()) {
            log.info("Delivery request {} created by {}", outcome.body().number(), user.username());
        }
        return outcome;
    }

    /**
     * Changes the comment and/or the assignee. Values equal to the current ones are a no-op (200, no
     * version bump, no rights or version check), so a repeated click — or a client that always sends
     * the whole form — does not get a conflict or a 403 for fields it did not change. Rights and
     * {@code version} are checked only for fields that actually change.
     */
    @Transactional
    public RequestCard update(CurrentUser user, UUID id, UpdateRequestBody body) {
        DeliveryRequest request = requests.findById(id)
                .filter(found -> RequestPermissions.canView(user, found.getOwnerId(), found.getServiceId()))
                .orElseThrow(() -> RequestQueryService.notFound(id));

        // The aggregate decides what changes. It is only an in-memory copy: if a check below fails,
        // it is discarded without being saved.
        Instant now = calendar.now();
        boolean commentChanged = body.comment().present()
                && request.changeComment(body.comment().value(), now);
        boolean assigneeChanged = body.assigneeId().present()
                && request.changeAssignee(body.assigneeId().value(), now);
        if (!commentChanged && !assigneeChanged) {
            return queries.getCard(id, user);
        }

        if (assigneeChanged && !RequestPermissions.canAssign(user, request.getServiceId())) {
            throw ApiException.forbidden("Only an operator of the request's service assigns it");
        }
        if (commentChanged && !RequestPermissions.canComment(user, request.getOwnerId(), request.getServiceId())) {
            throw ApiException.forbidden("You cannot comment on this request");
        }
        if (body.version() != request.getVersion()) {
            throw versionConflict(id, user);
        }
        if (assigneeChanged && request.getAssigneeId() != null) {
            checkAssignee(request.getAssigneeId(), request.getServiceId());
        }

        try {
            requests.save(request);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(id, user);
        }
        log.info("Delivery request {} updated by {} (comment: {}, assignee: {})",
                id, user.username(), commentChanged, assigneeChanged);
        return queries.getCard(id, user);
    }

    private IdempotentResponse<RequestCard> createNew(CurrentUser user, DeliveryContent content) {
        if (content.deadline().isBefore(calendar.today())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Some fields are invalid", Map.of("errors",
                    List.of(Map.of("field", "deadline", "message", "must not be before today"))));
        }
        Instant now = calendar.now();
        DeliveryRequest request = DeliveryRequest.create(ids.newId(), requests.nextNumber(), user.serviceId(),
                user.id(), content, now);
        requests.save(request);
        operations.insertPending(request.getId(), ids.newId(), PayloadHasher.hash(content), now);
        return new IdempotentResponse<>(HttpStatus.CREATED.value(), queries.getCard(request.getId(), user),
                request.getId());
    }

    private void checkAssignee(UUID assigneeId, UUID serviceId) {
        boolean valid = users.findById(assigneeId)
                .filter(candidate -> candidate.getRole() == Role.OPERATOR)
                .filter(candidate -> candidate.isEnabled())
                .filter(candidate -> candidate.getServiceId().equals(serviceId))
                .isPresent();
        if (!valid) {
            throw new ApiException(ErrorCode.INVALID_ASSIGNEE,
                    "Assignee must be an active operator of the request's service");
        }
    }

    private ApiException versionConflict(UUID id, CurrentUser user) {
        return new ApiException(ErrorCode.VERSION_CONFLICT,
                "The request was changed by someone else; review the current state and try again",
                Map.of("current", queries.getCard(id, user)));
    }
}
