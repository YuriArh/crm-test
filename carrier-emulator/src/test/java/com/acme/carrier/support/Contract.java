package com.acme.carrier.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;

import org.springframework.test.web.servlet.ResultMatcher;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;

/**
 * Checks that a response matches {@code contracts/carrier-api.yaml}: path, status code, field names, types,
 * required fields and enums. Requests are not checked, so tests can send invalid bodies on purpose.
 */
public final class Contract {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(System.getProperty("carrier.contract"))
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .build();

    private Contract() {
    }

    public static ResultMatcher matchesContract() {
        return openApi().isValid(VALIDATOR);
    }
}
