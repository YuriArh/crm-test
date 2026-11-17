package com.acme.dispatch.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ChangesTest {

    @Test
    void onlyChangedFieldsInTheirOrder() {
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();

        assertThat(new Changes()
                .field("comment", "Call first", "Call first")
                .field("assigneeId", before, after)
                .field("businessStatus", "AWAITING", "IN_PROGRESS")
                .list())
                .containsExactly(new Change("assigneeId", before.toString(), after.toString()),
                        new Change("businessStatus", "AWAITING", "IN_PROGRESS"));
    }

    @Test
    void nullsCompare() {
        assertThat(new Changes()
                .field("comment", null, "Call first")
                .field("rejectionReason", "No", null)
                .field("trackingNumber", (String) null, null)
                .list())
                .containsExactly(new Change("comment", null, "Call first"), new Change("rejectionReason", "No", null));
    }

    @Test
    void priceComparesByValue() {
        assertThat(new Changes().price("price", new BigDecimal("1500"), new BigDecimal("1500.00")).list()).isEmpty();
        assertThat(new Changes().price("price", null, new BigDecimal("1500.00")).list())
                .containsExactly(new Change("price", null, "1500"));
        assertThat(new Changes().price("price", new BigDecimal("612.50"), new BigDecimal("700")).list())
                .containsExactly(new Change("price", "612.5", "700"));
    }

    @Test
    void noChangesIsEmpty() {
        Changes changes = new Changes().field("comment", "A", "A").price("price", null, null);

        assertThat(changes.list()).isEmpty();
        assertThat(changes.isEmpty()).isTrue();
    }

    @Test
    void jsonRoundTrip() {
        java.util.List<Change> changes = java.util.List.of(new Change("comment", null, "Line\n\"quoted\""),
                new Change("price", "612.5", "700"));

        assertThat(Change.listOf(Change.toJson(changes))).isEqualTo(changes);
        assertThat(Change.toJson(changes)).startsWith("[{\"field\":\"comment\",\"from\":null,");
    }

    @Test
    void alwaysKeepsAnUnchangedField() {
        assertThat(new Changes().always("deliveryStatus", "UNKNOWN", "UNKNOWN").list())
                .containsExactly(new Change("deliveryStatus", "UNKNOWN", "UNKNOWN"));
    }
}
