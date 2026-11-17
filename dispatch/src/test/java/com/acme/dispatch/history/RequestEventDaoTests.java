package com.acme.dispatch.history;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class RequestEventDaoTests {

    @Autowired
    RequestEventDao dao;
    @Autowired
    TestRequests requests;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void insertsEvent() {
        RequestCard request = requests.create("1");
        UUID id = UUID.randomUUID();
        List<Change> changes = List.of(new Change("comment", null, "Call \"first\""),
                new Change("assigneeId", UUID.randomUUID().toString(), null));

        dao.insert(new RequestEvent(id, request.id(), 1L, START, NORTH_INITIATOR, RequestEventKind.EDITED, changes,
                null, null));

        assertThat(jdbc.sql("""
                        SELECT request_id || '/' || version || '/' || actor_id || '/' || kind
                               || '/' || (occurred_at = :start)
                          FROM request_event WHERE id = :id""")
                .param("id", id)
                .param("start", java.sql.Timestamp.from(START))
                .query(String.class).single())
                .isEqualTo(request.id() + "/1/" + NORTH_INITIATOR + "/EDITED/true");
        assertThat(Change.listOf(jdbc.sql("SELECT changes::text FROM request_event WHERE id = :id")
                .param("id", id).query(String.class).single())).isEqualTo(changes);
    }

    /** Events are only added: the DAO has no way to change or remove one. */
    @Test
    void daoOnlyInserts() {
        assertThat(Arrays.stream(RequestEventDao.class.getDeclaredMethods())
                .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName))
                .containsExactly("insert");
    }
}
