package com.acme.carrier.support;

import java.util.List;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
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

    /**
     * What is wrong with a {@code 200} JSON response of {@code GET path} according to the contract, one message per
     * violation (each names the offending JSON pointer). For fault modes that break the contract on purpose.
     */
    public static List<String> violations(String path, String body) {
        ValidationReport report = VALIDATOR.validateResponse(path, Request.Method.GET,
                SimpleResponse.Builder.status(200).withContentType("application/json").withBody(body).build());
        return report.getMessages().stream()
                .map(message -> message.getContext()
                        .flatMap(ValidationReport.MessageContext::getPointers)
                        .map(pointers -> pointers.getInstance() + " ")
                        .orElse("") + message.getMessage())
                .toList();
    }
}
