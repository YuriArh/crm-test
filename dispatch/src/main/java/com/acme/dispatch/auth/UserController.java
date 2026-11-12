package com.acme.dispatch.auth;

import java.util.List;

import com.acme.dispatch.common.web.ApiException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserRepository users;

    public UserController(AppUserRepository users) {
        this.users = users;
    }

    /** Enabled operators of the caller's own service — the choices for an assignee. Operators only. */
    @GetMapping("/operators")
    List<UserSummary> operators(@AuthenticationPrincipal CurrentUser user) {
        if (!user.isOperator()) {
            throw ApiException.forbidden("Only operators can list operators");
        }
        return users.findEnabledOperators(user.serviceId()).stream()
                .map(UserSummary::of)
                .toList();
    }
}
