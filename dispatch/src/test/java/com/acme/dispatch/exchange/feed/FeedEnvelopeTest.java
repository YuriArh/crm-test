package com.acme.dispatch.exchange.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

class FeedEnvelopeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void increasingPositionsAfterTheCursorAreAccepted() {
        assertThat(FeedEnvelope.violation(10, page("{\"feedSeq\": 11}", "{\"feedSeq\": 12}"))).isEmpty();
    }

    @Test
    void gapsBetweenPositionsAreAllowed() {
        assertThat(FeedEnvelope.violation(10, page("{\"feedSeq\": 15}", "{\"feedSeq\": 40}"))).isEmpty();
    }

    @Test
    void anEmptyPageIsAccepted() {
        assertThat(FeedEnvelope.violation(0, List.of())).isEmpty();
    }

    @Test
    void theContentOfTheRecordIsNotChecked() {
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 1, \"price\": \"nonsense\"}"))).isEmpty();
    }

    @Test
    void anElementThatIsNotAnObjectIsRejected() {
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 1}", "42")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("element 2").contains("not an object"));
    }

    @Test
    void aMissingPositionIsRejected() {
        assertThat(FeedEnvelope.violation(0, page("{\"messageId\": \"m\"}")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("element 1").contains("feedSeq"));
    }

    @Test
    void aPositionThatIsNotAnIntegerIsRejected() {
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": \"1\"}"))).isPresent();
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 1.5}"))).isPresent();
    }

    @Test
    void aPositionOutOfRangeIsRejectedWithoutThrowing() {
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 92233720368547758070}"))).isPresent();
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 0}"))).isPresent();
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": -3}"))).isPresent();
    }

    @Test
    void theFirstPositionMustBeAfterTheCursor() {
        assertThat(FeedEnvelope.violation(10, page("{\"feedSeq\": 10}")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("not after"));
        assertThat(FeedEnvelope.violation(10, page("{\"feedSeq\": 3}"))).isPresent();
    }

    @Test
    void positionsMustStrictlyIncrease() {
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 2}", "{\"feedSeq\": 2}"))).isPresent();
        assertThat(FeedEnvelope.violation(0, page("{\"feedSeq\": 5}", "{\"feedSeq\": 4}")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("element 2"));
    }

    private static List<JsonNode> page(String... entries) {
        return java.util.Arrays.stream(entries).map(JSON::readTree).toList();
    }
}
