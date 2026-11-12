package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateRequestBodyValidationTest {

    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 20);

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
    void validBodyHasNoViolations() {
        assertThat(validator.validate(body("Pallets", "12.5", "Main st 1", DEADLINE))).isEmpty();
    }

    @Test
    void blankTextFieldsAreRejected() {
        assertThat(fields(body(" ", "12.5", "", DEADLINE))).containsExactlyInAnyOrder("cargoDescription", "deliveryAddress");
    }

    @Test
    void missingFieldsAreRejected() {
        assertThat(fields(new CreateRequestBody(null, null, null, null)))
                .containsExactlyInAnyOrder("cargoDescription", "weightKg", "deliveryAddress", "deadline");
    }

    @Test
    void weightBoundsAreInclusive() {
        assertThat(validator.validate(body("Pallets", "0.1", "Main st 1", DEADLINE))).isEmpty();
        assertThat(validator.validate(body("Pallets", "20000", "Main st 1", DEADLINE))).isEmpty();
        assertThat(fields(body("Pallets", "0.0", "Main st 1", DEADLINE))).containsExactly("weightKg");
        assertThat(fields(body("Pallets", "20000.1", "Main st 1", DEADLINE))).containsExactly("weightKg");
    }

    @Test
    void weightHasAtMostOneDecimal() {
        assertThat(fields(body("Pallets", "1.25", "Main st 1", DEADLINE))).containsExactly("weightKg");
    }

    @Test
    void textLengthsAreLimited() {
        assertThat(fields(body("x".repeat(201), "1", "y".repeat(501), DEADLINE)))
                .containsExactlyInAnyOrder("cargoDescription", "deliveryAddress");
        assertThat(validator.validate(body("x".repeat(200), "1", "y".repeat(500), DEADLINE))).isEmpty();
    }

    @Test
    void contentIsStripped() {
        DeliveryContent content = body("  Pallets ", "12.5", " Main st 1 ", DEADLINE).toContent();

        assertThat(content.cargoDescription()).isEqualTo("Pallets");
        assertThat(content.deliveryAddress()).isEqualTo("Main st 1");
    }

    private static CreateRequestBody body(String cargo, String weight, String address, LocalDate deadline) {
        return new CreateRequestBody(cargo, new BigDecimal(weight), address, deadline);
    }

    private static Set<String> fields(CreateRequestBody body) {
        Set<ConstraintViolation<CreateRequestBody>> violations = validator.validate(body);
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
