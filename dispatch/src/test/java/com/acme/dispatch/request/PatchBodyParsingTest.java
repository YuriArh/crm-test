package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PatchBodyParsingTest {

    static final UUID ASSIGNEE = UUID.fromString("0195f000-0000-7000-8000-000000000102");

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void omittedFieldMeansKeep() {
        UpdateRequestBody body = parse("{\"version\":3,\"comment\":\"Fragile\"}");

        assertThat(body.version()).isEqualTo(3);
        assertThat(body.assigneeId().present()).isFalse();
        assertThat(body.comment()).isEqualTo(FieldChange.of("Fragile"));
    }

    @Test
    void explicitNullMeansClear() {
        UpdateRequestBody body = parse("{\"version\":3,\"assigneeId\":null}");

        assertThat(body.assigneeId().present()).isTrue();
        assertThat(body.assigneeId().value()).isNull();
        assertThat(body.comment().present()).isFalse();
    }

    @Test
    void assigneeIsParsedAsUuid() {
        UpdateRequestBody body = parse("{\"version\":0,\"assigneeId\":\"" + ASSIGNEE + "\"}");

        assertThat(body.assigneeId()).isEqualTo(FieldChange.of(ASSIGNEE));
    }

    @Test
    void blankCommentIsStoredAsNoComment() {
        assertThat(parse("{\"version\":0,\"comment\":\"   \"}").comment()).isEqualTo(FieldChange.of(null));
    }

    @Test
    void commentIsStripped() {
        assertThat(parse("{\"version\":0,\"comment\":\"  Fragile  \"}").comment()).isEqualTo(FieldChange.of("Fragile"));
    }

    @Test
    void versionIsRequired() {
        assertInvalid("{\"comment\":\"x\"}", "version");
    }

    @Test
    void versionMustBeANonNegativeInteger() {
        assertInvalid("{\"version\":\"3\",\"comment\":\"x\"}", "version");
        assertInvalid("{\"version\":-1,\"comment\":\"x\"}", "version");
        assertInvalid("{\"version\":1.5,\"comment\":\"x\"}", "version");
    }

    @Test
    void atLeastOneChangeIsRequired() {
        assertInvalid("{\"version\":1}", "body");
    }

    @Test
    void unknownFieldsAreRejected() {
        assertInvalid("{\"version\":1,\"comments\":\"typo\"}", "comments");
    }

    @Test
    void contentFieldsCannotBePatched() {
        assertInvalid("{\"version\":1,\"deliveryAddress\":\"Elsewhere\"}", "deliveryAddress");
    }

    @Test
    void assigneeMustBeAUuid() {
        assertInvalid("{\"version\":1,\"assigneeId\":\"not-a-uuid\"}", "assigneeId");
        assertInvalid("{\"version\":1,\"assigneeId\":42}", "assigneeId");
    }

    @Test
    void commentHasAMaximumLength() {
        assertInvalid("{\"version\":1,\"comment\":\"" + "x".repeat(2001) + "\"}", "comment");
        assertThat(parse("{\"version\":1,\"comment\":\"" + "x".repeat(2000) + "\"}").comment().value()).hasSize(2000);
    }

    @Test
    void commentLengthIsMeasuredOnTheStrippedText() {
        String text = "x".repeat(1990) + " ".repeat(20);

        assertThat(parse("{\"version\":1,\"comment\":\"" + text + "\"}").comment().value()).hasSize(1990);
    }

    @Test
    void commentLengthCountsCharactersNotUtf16Units() {
        String emoji = "😀"; // one character, two UTF-16 units
        String fits = emoji.repeat(2000);

        assertThat(parse("{\"version\":1,\"comment\":\"" + fits + "\"}").comment().value()).isEqualTo(fits);
        assertInvalid("{\"version\":1,\"comment\":\"" + emoji.repeat(2001) + "\"}", "comment");
    }

    @Test
    void bodyMustBeAnObject() {
        assertInvalid("[1,2]", "body");
    }

    private UpdateRequestBody parse(String body) {
        return UpdateRequestBody.parse(json.readTree(body));
    }

    @SuppressWarnings("unchecked")
    private void assertInvalid(String body, String field) {
        assertThatThrownBy(() -> parse(body))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    List<Map<String, Object>> errors = (List<Map<String, Object>>) ex.properties().get("errors");
                    assertThat(errors).extracting(error -> error.get("field")).contains(field);
                });
    }
}
