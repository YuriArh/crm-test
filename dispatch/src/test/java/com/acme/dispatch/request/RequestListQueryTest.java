package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.exchange.DeliveryStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestListQueryTest {

    @Test
    void defaults() {
        RequestListQuery query = RequestListQuery.parse(null, null, null, null, null, null);

        assertThat(query.businessStatus()).isNull();
        assertThat(query.deliveryStatus()).isNull();
        assertThat(query.overdue()).isNull();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sortField()).isEqualTo(RequestListQuery.SortField.CREATED_AT);
        assertThat(query.ascending()).isFalse();
        assertThat(query.offset()).isZero();
        assertThat(RequestListQuery.defaults()).isEqualTo(query);
    }

    @Test
    void blankValuesMeanDefaults() {
        assertThat(RequestListQuery.parse("", " ", "", "", "", "")).isEqualTo(RequestListQuery.defaults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-5", "abc"})
    void sizeOutOfRangeIsRejected(String size) {
        assertInvalid(() -> RequestListQuery.parse(null, null, null, null, size, null), "size");
    }

    @Test
    void sizeBoundsAreAccepted() {
        assertThat(RequestListQuery.parse(null, null, null, null, "1", null).size()).isEqualTo(1);
        assertThat(RequestListQuery.parse(null, null, null, null, "100", null).size()).isEqualTo(100);
    }

    @Test
    void negativePageIsRejected() {
        assertInvalid(() -> RequestListQuery.parse(null, null, null, "-1", null, null), "page");
    }

    @Test
    void offsetIsPageTimesSize() {
        assertThat(RequestListQuery.parse(null, null, null, "3", "25", null).offset()).isEqualTo(75);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cargoDescription,asc", "id", "r.created_at;drop table x", ",desc"})
    void unknownSortFieldIsRejected(String sort) {
        assertInvalid(() -> RequestListQuery.parse(null, null, null, null, null, sort), "sort field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline,up", "deadline,", "deadline,asc,desc"})
    void badSortDirectionIsRejected(String sort) {
        assertInvalid(() -> RequestListQuery.parse(null, null, null, null, null, sort), "sort");
    }

    @Test
    void validSortIsAccepted() {
        RequestListQuery query = RequestListQuery.parse(null, null, null, null, null, "deadline,asc");

        assertThat(query.sortField()).isEqualTo(RequestListQuery.SortField.DEADLINE);
        assertThat(query.sortField().column()).isEqualTo("r.deadline");
        assertThat(query.ascending()).isTrue();

        RequestListQuery byNumber = RequestListQuery.parse(null, null, null, null, null, "number,DESC");
        assertThat(byNumber.sortField()).isEqualTo(RequestListQuery.SortField.NUMBER);
        assertThat(byNumber.ascending()).isFalse();

        assertThat(RequestListQuery.parse(null, null, null, null, null, "createdAt").ascending()).isTrue();
    }

    @Test
    void enumsAndBooleanAreParsed() {
        RequestListQuery query = RequestListQuery.parse("SUCCEEDED", "ACKNOWLEDGED", "true", null, null, null);

        assertThat(query.businessStatus()).isEqualTo(BusinessStatus.SUCCEEDED);
        assertThat(query.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(query.overdue()).isTrue();
        assertThat(RequestListQuery.parse(null, null, "FALSE", null, null, null).overdue()).isFalse();
    }

    @Test
    void unknownEnumValuesAreRejected() {
        assertInvalid(() -> RequestListQuery.parse("DONE", null, null, null, null, null), "businessStatus");
        assertInvalid(() -> RequestListQuery.parse(null, "SENT", null, null, null, null), "deliveryStatus");
        assertInvalid(() -> RequestListQuery.parse(null, null, "yes", null, null, null), "overdue");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
