package com.acme.dispatch.exchange.feed;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.atFeedSeq;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.raw;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class FeedPollerTests {

    @Autowired
    FeedPoller poller;
    @Autowired
    FeedCursorDao cursor;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    TestRequests requests;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        clock.set(START);
        carrier.reset();
        poller.resetPause();
        cursor.move(0, START);
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
        cursor.move(0, START);
    }

    @Test
    void pageIsAppliedAndTheCursorMoves() {
        RequestCard request = requests.create("12.5");
        carrier.publish(inProgress(1, request.operationId()), succeeded(2, request.operationId(), "TRK-1", "612.50"));

        assertThat(poller.runOnce()).isEqualTo(2);

        assertThat(cursor.read()).isEqualTo(2);
        assertThat(status(request)).isEqualTo("SUCCEEDED");
        assertThat(poller.runOnce()).as("nothing new").isZero();
    }

    @Test
    void nextPollContinuesFromTheStoredCursor() {
        RequestCard request = requests.create("1");
        carrier.publish(inProgress(1, request.operationId()));
        poller.runOnce();

        // As after a restart: the cursor is in the database, the feed has grown.
        carrier.publish(succeeded(2, request.operationId(), "TRK-1", "325.00"));
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(cursor.read()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*), sum(duplicate_count) FROM inbox")
                .query((rs, n) -> rs.getInt(1) + "/" + rs.getInt(2)).single()).isEqualTo("2/0");
    }

    @Test
    void malformedEntryDoesNotBlockTheFeed() {
        RequestCard request = requests.create("1");
        carrier.publish(raw("{\"feedSeq\": 1, \"messageId\": \"broken\"}"),
                succeeded(2, request.operationId(), "TRK-1", "325.00"));

        assertThat(poller.runOnce()).isEqualTo(2);

        assertThat(cursor.read()).isEqualTo(2);
        assertThat(status(request)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT processing_status FROM inbox WHERE message_id = 'broken'")
                .query(String.class).single()).isEqualTo("INVALID");
    }

    @Test
    void duplicateDeliveryInTheFeedHasNoSecondEffect() {
        RequestCard request = requests.create("1");
        JsonNode result = succeeded(1, request.operationId(), "TRK-1", "325.00");
        carrier.publish(result, atFeedSeq(result, 2));

        poller.runOnce();

        assertThat(jdbc.sql("SELECT version FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT duplicate_count FROM inbox WHERE operation_id = :id")
                .param("id", request.operationId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(cursor.read()).isEqualTo(2);
    }

    @Test
    void fullPagesAreReadOneAfterAnother() {
        for (int i = 1; i <= 250; i++) {
            carrier.publish(inProgress(i, UUID.randomUUID()));
        }

        assertThat(poller.runOnce()).isEqualTo(250);
        assertThat(cursor.read()).isEqualTo(250);
    }

    @Test
    void unavailableCarrierKeepsTheCursorAndBacksOff() {
        RequestCard request = requests.create("1");
        carrier.publish(inProgress(1, request.operationId()));
        carrier.feedUnavailable(true);

        assertThat(poller.runOnce()).isZero();
        assertThat(cursor.read()).isZero();

        carrier.feedUnavailable(false);
        assertThat(poller.runOnce()).as("still pausing for 1 s").isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(poller.runOnce()).isEqualTo(1);
        assertThat(cursor.read()).isEqualTo(1);
    }

    @Test
    void pauseGrowsWhileTheCarrierStaysUnavailable() {
        carrier.feedUnavailable(true);
        poller.runOnce();                                     // pause 1 s
        clock.advance(Duration.ofSeconds(1));
        poller.runOnce();                                     // pause 2 s
        carrier.feedUnavailable(false);
        carrier.publish(inProgress(1, UUID.randomUUID()));

        clock.advance(Duration.ofSeconds(1));
        assertThat(poller.runOnce()).as("1 s into a 2 s pause").isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(poller.runOnce()).isEqualTo(1);
    }

    private String status(RequestCard request) {
        return jdbc.sql("SELECT business_status FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(String.class).single();
    }
}
