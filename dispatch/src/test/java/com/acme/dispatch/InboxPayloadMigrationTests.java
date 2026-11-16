package com.acme.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.acme.dispatch.support.IntegrationTest;

import tools.jackson.databind.json.JsonMapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V7 turns {@code inbox.payload} from jsonb into text. Rows stored before must keep their content: the migrations
 * are run up to V6 in a separate schema, a row is stored, then V7 is applied.
 */
@IntegrationTest
class InboxPayloadMigrationTests {

    private static final String SCHEMA = "v7_payload_check";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void storedRecordsKeepTheirContent() {
        String record = """
                {"feedSeq": 41, "messageId": "m-1", "status": "SUCCEEDED", "price": 612.50,
                 "trackingNumber": "TRK-1", "reason": "Груз «хрупкий»", "nested": {"a": [1, 2]}}""";
        migrate("6");
        jdbc.sql("""
                        INSERT INTO %s.inbox (id, message_id, feed_seq, payload, source, processing_status, received_at)
                        VALUES (gen_random_uuid(), 'm-1', 41, CAST(:payload AS jsonb), 'FEED', 'APPLIED', now())"""
                        .formatted(SCHEMA))
                .param("payload", record)
                .update();

        migrate("7");

        String payload = jdbc.sql("SELECT payload FROM " + SCHEMA + ".inbox WHERE message_id = 'm-1'")
                .query(String.class).single();
        assertThat(JSON.readTree(payload)).isEqualTo(JSON.readTree(record));
        assertThat(jdbc.sql("SELECT attempts || ':' || coalesce(next_attempt_at::text, '-') FROM " + SCHEMA
                + ".inbox WHERE message_id = 'm-1'").query(String.class).single()).isEqualTo("0:-");
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
