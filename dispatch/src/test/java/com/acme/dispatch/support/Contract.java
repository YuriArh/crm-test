package com.acme.dispatch.support;

import java.nio.file.Files;
import java.nio.file.Path;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;

import org.springframework.test.web.servlet.ResultMatcher;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.allOf;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.headerContainsRegexp;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.messageHasKey;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.messageContainsSubstring;

/**
 * Checks MockMvc calls against {@code contracts/dispatch-api.yaml}.
 *
 * <ul>
 *   <li>Every path and method a test calls must be in the contract, so an endpoint added without a contract
 *       change fails the build. Exceptions: actuator, and calls marked with {@link #SKIP_HEADER}.</li>
 *   <li>Every response must match: status code, field names, types, required fields and enums.</li>
 *   <li>Request bodies, parameters and headers are not checked, so tests can send invalid input on purpose.</li>
 * </ul>
 *
 * <p>Applied to every MockMvc call of {@link IntegrationTest} by {@link ContractConfiguration}.
 */
public final class Contract {

    /**
     * Header a test sets, with the reason as its value, when it calls outside the contract on purpose
     * (e.g. to check the answer to an unknown path or method).
     */
    public static final String SKIP_HEADER = "X-Contract-Skip";

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(specificationPath())
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    // More specific keys win: an undescribed path or method is still an error.
                    .withLevel("validation.request.path.missing", ValidationReport.Level.ERROR)
                    .withLevel("validation.request.operation.notAllowed", ValidationReport.Level.ERROR)
                    .build())
            .withWhitelist(ValidationErrorsWhitelist.create()
                    .withRule("Actuator is not part of the API contract",
                            allOf(messageHasKey("validation.request.path.missing"),
                                    // Path rules see the contract's path, which a missing path does not have.
                                    messageContainsSubstring("request '/actuator/")))
                    .withRule("A test calls outside the contract on purpose",
                            headerContainsRegexp(SKIP_HEADER, ".+")))
            .build();

    private Contract() {
    }

    public static ResultMatcher matchesContract() {
        return openApi().isValid(VALIDATOR);
    }

    /**
     * Set by Gradle ({@code dispatch.contract}); when tests run elsewhere (e.g. an IDE's own JUnit runner),
     * the file is looked up from the working directory upwards.
     */
    private static String specificationPath() {
        String configured = System.getProperty("dispatch.contract");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/dispatch-api.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("contracts/dispatch-api.yaml not found; set -Ddispatch.contract=<path>");
    }
}
