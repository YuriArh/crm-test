package com.acme.dispatch.problem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/problems/{id}/resolve}. The comment is checked as it will be stored: stripped. */
public record ResolveProblemBody(@NotNull ProblemDecision decision, @NotBlank @Size(max = 2000) String comment) {

    public ResolveProblemBody {
        comment = comment == null ? null : comment.strip();
    }
}
