package com.acme.dispatch.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.acme.dispatch.exchange.inbox.ResultMessage;

import org.junit.jupiter.api.Test;

class ResultSnapshotTest {

    @Test
    void onlyFinalStatusesHaveSnapshot() {
        assertThat(ResultSnapshot.of("AWAITING", null, null, null)).isNull();
        assertThat(ResultSnapshot.of("IN_PROGRESS", null, null, null)).isNull();
        assertThat(ResultSnapshot.of(null, null, null, null)).isNull();
    }

    @Test
    void keepsTheFields() {
        assertThat(ResultSnapshot.of("SUCCEEDED", "TRK-00000001", new BigDecimal("612.50"), null))
                .isEqualTo(new ResultSnapshot("SUCCEEDED", "TRK-00000001", new BigDecimal("612.50"), null));
        assertThat(ResultSnapshot.of("REJECTED", null, null, "Too heavy"))
                .isEqualTo(new ResultSnapshot("REJECTED", null, null, "Too heavy"));
    }

    @Test
    void contentIsTheComparisonKeyOfAResult() {
        ResultSnapshot snapshot = ResultSnapshot.of("SUCCEEDED", "TRK-00000001", new BigDecimal("612.50"), null);

        assertThat(snapshot.content())
                .isEqualTo(ResultMessage.content("SUCCEEDED", "TRK-00000001", new BigDecimal("612.5"), null));
    }

    @Test
    void contentOfNoResultIsEmpty() {
        assertThat(ResultSnapshot.contentOf(null)).isNull();
        assertThat(ResultSnapshot.contentOf(new ResultSnapshot("REJECTED", null, null, "No")))
                .isEqualTo("REJECTED|||No");
    }
}
