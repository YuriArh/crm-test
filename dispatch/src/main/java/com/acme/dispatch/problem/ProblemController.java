package com.acme.dispatch.problem;

import java.util.List;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.PageResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Problems of the exchange for operators ({@code docs/specs/operator-problems.md} §2.5). */
@RestController
@RequestMapping("/api/problems")
class ProblemController {

    private final ProblemQueryDao queries;

    ProblemController(ProblemQueryDao queries) {
        this.queries = queries;
    }

    /** One page; the page and the total are read in one snapshot. */
    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<ExchangeProblem> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) List<String> kind,
                                              @RequestParam(required = false) String page,
                                              @RequestParam(required = false) String size,
                                              @AuthenticationPrincipal CurrentUser user) {
        requireOperator(user);
        ProblemListQuery query = ProblemListQuery.parse(status, kind, page, size);
        return PageResponse.of(queries.findPage(user, query), query.page(), query.size(), queries.count(user, query));
    }

    /**
     * One problem in the operator's scope (web-problems.md §3.1). Another service's, a missing and a malformed id all
     * answer {@code 404}, so the existence of a problem outside the scope is not revealed.
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ExchangeProblem one(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) {
        requireOperator(user);
        UUID problemId = problemId(id);
        return queries.find(problemId, user).orElseThrow(() -> notFound(id));
    }

    /** A malformed id cannot name an existing problem, so it gets the same 404 as a missing one. */
    static UUID problemId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound(id);
        }
    }

    static ApiException notFound(Object id) {
        return ApiException.notFound("Problem " + id + " not found");
    }

    static void requireOperator(CurrentUser user) {
        if (!user.isOperator()) {
            throw ApiException.forbidden("Only operators work with problems");
        }
    }
}
