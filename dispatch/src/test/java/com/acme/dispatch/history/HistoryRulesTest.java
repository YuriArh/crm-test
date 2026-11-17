package com.acme.dispatch.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

import org.junit.jupiter.api.Test;

class HistoryRulesTest {

    @Test
    void initiatorSeesTheBusinessPart() {
        assertThat(HistoryRules.visibleKinds(false)).containsExactlyInAnyOrder(HistoryKind.CREATED,
                HistoryKind.EDITED, HistoryKind.RESULT_CHANGED, HistoryKind.DELIVERY_ACKNOWLEDGED);
    }

    @Test
    void operatorSeesEverything() {
        assertThat(HistoryRules.visibleKinds(true)).containsExactlyInAnyOrder(HistoryKind.values());
    }

    /** Things that happen at one instant (one transaction) are listed in the order they happen. */
    @Test
    void kindsAtOneInstantFollowCause() {
        assertThat(HistoryKind.values()).containsExactly(HistoryKind.CREATED, HistoryKind.DELIVERY_RETRIED,
                HistoryKind.SEND_ATTEMPT, HistoryKind.RECONCILIATION, HistoryKind.RESULT_RECEIVED,
                HistoryKind.RESULT_CHANGED, HistoryKind.DELIVERY_ACKNOWLEDGED, HistoryKind.EDITED,
                HistoryKind.PROBLEM_OPENED, HistoryKind.PROBLEM_RESOLVED);
    }

    @Test
    void cursorRoundTrip() {
        HistoryCursor cursor = new HistoryCursor(Instant.parse("2026-11-12T09:00:05.123456Z"),
                HistoryKind.SEND_ATTEMPT, UUID.randomUUID());

        assertThat(HistoryCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void brokenCursorIsAValidationError() {
        assertThatThrownBy(() -> HistoryCursor.parse("not a cursor"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> HistoryCursor.parse(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1:UNKNOWN_KIND:x".getBytes())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void limitDefaultsAndBounds() {
        assertThat(HistoryRules.limit(null)).isEqualTo(50);
        assertThat(HistoryRules.limit("1")).isEqualTo(1);
        assertThat(HistoryRules.limit("200")).isEqualTo(200);
        for (String bad : new String[] {"0", "201", "x"}) {
            assertThatThrownBy(() -> HistoryRules.limit(bad))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }
}
