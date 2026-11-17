package com.acme.dispatch.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ProblemKindTest {

    @Test
    void kindsWithOurSideKeepItsSnapshot() {
        assertThat(Arrays.stream(ProblemKind.values()).filter(ProblemKind::hasOurSide))
                .containsExactlyInAnyOrder(ProblemKind.RESULT_CONFLICT, ProblemKind.RECONCILIATION_CONFLICT,
                        ProblemKind.EXTERNAL_MISSING);
    }
}
