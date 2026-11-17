package com.acme.dispatch.request;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.PageResponse;
import com.acme.dispatch.exchange.reconcile.ReconciliationQueryDao;
import com.acme.dispatch.problem.ExchangeProblem;
import com.acme.dispatch.problem.ProblemKind;
import com.acme.dispatch.problem.ProblemQueryDao;
import com.acme.dispatch.problem.ProblemRules;
import com.acme.dispatch.problem.ProblemStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the requests API: the registry and the request card, always within the viewer's scope. */
@Service
public class RequestQueryService {

    /** How many latest reconciliation checks the card shows. */
    static final int CARD_RECONCILIATIONS = 20;

    private final RequestQueryDao dao;
    private final BusinessCalendar calendar;
    private final ProblemQueryDao problems;
    private final ReconciliationQueryDao reconciliations;

    public RequestQueryService(RequestQueryDao dao, BusinessCalendar calendar, ProblemQueryDao problems,
                               ReconciliationQueryDao reconciliations) {
        this.dao = dao;
        this.calendar = calendar;
        this.problems = problems;
        this.reconciliations = reconciliations;
    }

    /** One registry page; the page and the total are read in one snapshot. */
    @Transactional(readOnly = true)
    public PageResponse<RequestListItem> list(RequestListQuery query, CurrentUser viewer) {
        LocalDate today = calendar.today();
        List<RequestListItem> items = dao.findPage(viewer, query, today);
        long total = dao.count(viewer, query, today);
        return PageResponse.of(items, query.page(), query.size(), total);
    }

    /**
     * The request card as {@code viewer} may see it: the exchange block, problems and actions only for operators.
     * A request the viewer may not see is reported exactly like a missing one.
     *
     * @throws ApiException {@code NOT_FOUND} if the request does not exist or is outside the viewer's scope
     */
    @Transactional(readOnly = true)
    public RequestCard getCard(UUID id, CurrentUser viewer) {
        RequestQueryDao.CardRow row = dao.findCard(id, calendar.today())
                .filter(found -> RequestPermissions.canView(viewer, found.ownerId(), found.serviceId()))
                .orElseThrow(() -> notFound(id));
        RequestCard card = row.card();
        if (!viewer.isOperator()) {
            return card.forInitiator();
        }
        List<ExchangeProblem> requestProblems = problems.forRequest(id);
        Set<ProblemKind> open = requestProblems.stream()
                .filter(problem -> problem.status() == ProblemStatus.OPEN)
                .map(ExchangeProblem::kind)
                .collect(Collectors.toUnmodifiableSet());
        boolean hasResult = card.businessStatus() == BusinessStatus.SUCCEEDED
                || card.businessStatus() == BusinessStatus.REJECTED;
        return card.forOperator(requestProblems,
                ProblemRules.requestActions(card.deliveryStatus(), open, hasResult),
                reconciliations.forRequest(id, CARD_RECONCILIATIONS));
    }

    static ApiException notFound(Object id) {
        return ApiException.notFound("Request " + id + " not found");
    }
}
