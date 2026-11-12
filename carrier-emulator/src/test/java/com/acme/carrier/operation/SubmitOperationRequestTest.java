package com.acme.carrier.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SubmitOperationRequestTest {

    static ValidatorFactory factory;
    static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequestHasNoViolations() {
        assertThat(validator.validate(request("Pallets", "12.5", "Main st 1"))).isEmpty();
    }

    @Test
    void missingFieldsAreRejected() {
        assertThat(fields(new SubmitOperationRequest(null, null, null, null, null)))
                .containsExactlyInAnyOrder("operationId", "cargoDescription", "weightKg", "deliveryAddress", "deadline");
    }

    @Test
    void weightRangeAndPrecision() {
        assertThat(validator.validate(request("a", "0.1", "b"))).isEmpty();
        assertThat(validator.validate(request("a", "20000", "b"))).isEmpty();
        assertThat(fields(request("a", "0", "b"))).containsExactly("weightKg");
        assertThat(fields(request("a", "20000.1", "b"))).containsExactly("weightKg");
        assertThat(fields(request("a", "1.25", "b"))).containsExactly("weightKg");
        assertThat(validator.validate(request("a", "1.20", "b"))).isEmpty();
    }

    @Test
    void textIsStrippedAndLimited() {
        SubmitOperationRequest request = request("  Pallets ", "1", " Main st 1 ");
        assertThat(request.cargoDescription()).isEqualTo("Pallets");
        assertThat(request.deliveryAddress()).isEqualTo("Main st 1");

        assertThat(fields(request("   ", "1", "x".repeat(501)))).containsExactlyInAnyOrder("cargoDescription", "deliveryAddress");
        assertThat(validator.validate(request("x".repeat(200), "1", "y".repeat(500)))).isEmpty();
    }

    @Test
    void weightIsCanonical() {
        assertThat(request("a", "12.50", "b").weightKg()).hasToString("12.5");
        assertThat(request("a", "2E+4", "b").weightKg()).hasToString("20000");
    }

    private static SubmitOperationRequest request(String cargo, String weight, String address) {
        return new SubmitOperationRequest(UUID.randomUUID(), cargo, new BigDecimal(weight), address,
                LocalDate.of(2026, 12, 1));
    }

    private static Set<String> fields(SubmitOperationRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
