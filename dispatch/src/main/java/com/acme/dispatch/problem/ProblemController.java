package com.acme.dispatch.problem;

import java.util.List;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.PageResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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

    static void requireOperator(CurrentUser user) {
        if (!user.isOperator()) {
            throw ApiException.forbidden("Only operators work with problems");
        }
    }
}
