package com.acme.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V14 creates the tables of Spring Session JDBC (docs/specs/jdbc-sessions.md §2.2). Their layout is the framework's;
 * the constraints are named here and checked: the session store relies on them.
 */
@IntegrationTest
class HttpSessionSchemaTests {

    /** Marks the rows of these tests so that cleaning up leaves the sessions of other tests alone. */
    private static final String MARK = "schema-test";

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void deleteRows() {
        jdbc.sql("DELETE FROM spring_session WHERE principal_name = :mark").param("mark", MARK).update();
    }

    @Test
    void attributeWithoutASessionIsRejected() {
        assertThatThrownBy(() -> attribute(newId(), "SPRING_SECURITY_CONTEXT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("spring_session_attributes_fk");
    }

    @Test
    void deletingASessionDeletesItsAttributes() {
        String id = newId();
        session(id, newId());
        attribute(id, "SPRING_SECURITY_CONTEXT");
        attribute(id, "other");
        assertThat(attributesOf(id)).isEqualTo(2);

        jdbc.sql("DELETE FROM spring_session WHERE primary_id = :id").param("id", id).update();

        assertThat(attributesOf(id)).isZero();
    }

    @Test
    void sessionIdIsUnique() {
        String sessionId = newId();
        session(newId(), sessionId);

        assertThatThrownBy(() -> session(newId(), sessionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("spring_session_ix1");
    }

    private void session(String primaryId, String sessionId) {
        jdbc.sql("""
                        INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
                                                    max_inactive_interval, expiry_time, principal_name)
                        VALUES (:primaryId, :sessionId, 0, 0, 1800, 1800000, :mark)
                        """)
                .param("primaryId", primaryId)
                .param("sessionId", sessionId)
                .param("mark", MARK)
                .update();
    }

    private void attribute(String primaryId, String name) {
        jdbc.sql("""
                        INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                        VALUES (:primaryId, :name, :bytes)
                        """)
                .param("primaryId", primaryId)
                .param("name", name)
                .param("bytes", new byte[] {1, 2, 3})
                .update();
    }

    private int attributesOf(String primaryId) {
        return jdbc.sql("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id = :id")
                .param("id", primaryId)
                .query(Integer.class)
                .single();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
