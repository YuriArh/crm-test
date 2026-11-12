package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequestNumberTest {

    @Test
    void padsToSixDigits() {
        assertThat(RequestNumber.format(123)).isEqualTo("DR-000123");
    }

    @Test
    void keepsLongerNumbers() {
        assertThat(RequestNumber.format(1_234_567)).isEqualTo("DR-1234567");
    }

    @Test
    void rejectsNonPositive() {
        assertThatThrownBy(() -> RequestNumber.format(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
