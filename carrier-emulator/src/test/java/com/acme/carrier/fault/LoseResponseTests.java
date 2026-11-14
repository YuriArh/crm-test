package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static com.acme.carrier.support.Operations.submitJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.acme.carrier.config.CarrierProperties;
import com.acme.carrier.execution.Executor;
import com.acme.carrier.operation.AdminOperation;
import com.acme.carrier.operation.OperationDao;
import com.acme.carrier.operation.OperationStateValue;
import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class LoseResponseTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FaultModes faults;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeSleeper sleeper;
    @Autowired
    Executor executor;
    @Autowired
    OperationDao operations;
    @Autowired
    CarrierProperties properties;

    Duration delay;

    @BeforeEach
    void setUp() {
        delay = properties.faults().loseResponseDelay();
    }

    @AfterEach
    void cleanUp() {
        TestRows.resetState(faults, clock, sleeper);
        TestRows.deleteAll(jdbc);
    }

    private void loseResponseOn() {
        faults.set(new Faults(false, true, false, false));
    }

    /** During each pause the clock moves by the pause, as it would with real sleeping. */
    private void clockFollowsPauses() {
        sleeper.onSleep(() -> clock.advance(sleeper.pauses().getLast()));
    }

    private static Duration total(List<Duration> pauses) {
        return pauses.stream().reduce(Duration.ZERO, Duration::plus);
    }

    @Test
    void receiptIsCommittedBeforeTheResponseIsHeld() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();
        List<Optional<AdminOperation>> seenFromOtherConnection = new CopyOnWriteArrayList<>();
        List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        sleeper.onSleep(() -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            // Another thread uses another connection: it sees only committed rows.
            seenFromOtherConnection.add(CompletableFuture.supplyAsync(() -> operations.find(id)).join());
            clock.advance(sleeper.pauses().getLast());
        });

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());

        assertThat(seenFromOtherConnection).isNotEmpty()
                .allSatisfy(found -> assertThat(found).hasValueSatisfying(operation -> {
                    assertThat(operation.state()).isEqualTo(OperationStateValue.ACCEPTED);
                    assertThat(operation.receivedCount()).isEqualTo(1);
                }));
        assertThat(transactionActive).containsOnly(false);
    }

    @Test
    void responseComesOnlyAfterExecutionAndTheDelay() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();
        List<OperationStateValue> statesDuringHold = new CopyOnWriteArrayList<>();
        sleeper.onSleep(() -> {
            clock.advance(sleeper.pauses().getLast());
            // The executor picks the operation up during the third pause.
            if (sleeper.pauses().size() == 3) {
                assertThat(executor.runOnce()).isEqualTo(1);
            }
            statesDuringHold.add(operations.find(id).orElseThrow().state());
        });

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.operationId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        List<Duration> pauses = sleeper.pauses();
        // Polling at a short interval until executed, then one pause for the rest of the delay.
        assertThat(pauses.subList(0, 3)).containsOnly(LostResponse.POLL_INTERVAL);
        assertThat(pauses).hasSize(4);
        assertThat(total(pauses)).isEqualTo(delay);
        assertThat(statesDuringHold.getLast()).isEqualTo(OperationStateValue.EXECUTED);
        AdminOperation stored = operations.find(id).orElseThrow();
        assertThat(stored.state()).isEqualTo(OperationStateValue.EXECUTED);
        assertThat(stored.executionCount()).isEqualTo(1);
    }

    @Test
    void responseWaitsForTheDelayEvenIfExecutedAtOnce() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();
        sleeper.onSleep(() -> {
            executor.runOnce();
            clock.advance(sleeper.pauses().getLast());
        });

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());

        assertThat(total(sleeper.pauses())).isEqualTo(delay);
        assertThat(operations.find(id).orElseThrow().state()).isEqualTo(OperationStateValue.EXECUTED);
    }

    @Test
    void repeatAnswersAtOnceAndIsNotExecutedAgain() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();
        sleeper.onSleep(() -> {
            executor.runOnce();
            clock.advance(sleeper.pauses().getLast());
        });
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        sleeper.reset();

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(sleeper.pauses()).isEmpty();
        assertThat(executor.runOnce()).isZero();
        AdminOperation stored = operations.find(id).orElseThrow();
        assertThat(stored.receivedCount()).isEqualTo(2);
        assertThat(stored.state()).isEqualTo(OperationStateValue.EXECUTED);
        assertThat(stored.executionCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM operation").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void conflictAndInvalidBodyAnswerAtOnce() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        loseResponseOn();

        Map<String, Object> other = body(id);
        other.put("cargoDescription", "Boxes");
        mockMvc.perform(submit(other))
                .andExpect(status().isConflict())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("PAYLOAD_MISMATCH"));

        mockMvc.perform(submitJson("{\"operationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract());

        assertThat(sleeper.pauses()).isEmpty();
    }

    @Test
    void responseComesAfterTheDelayEvenIfNeverExecuted() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();
        clockFollowsPauses();

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());

        List<Duration> pauses = sleeper.pauses();
        assertThat(total(pauses)).isEqualTo(delay);
        assertThat(pauses).allSatisfy(pause -> assertThat(pause).isLessThanOrEqualTo(LostResponse.POLL_INTERVAL));
        assertThat(operations.find(id).orElseThrow().state()).isEqualTo(OperationStateValue.ACCEPTED);
    }

    @Test
    void holdEndsEvenIfTheClockStandsStill() throws Exception {
        loseResponseOn();
        UUID id = UUID.randomUUID();

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());

        assertThat(total(sleeper.pauses())).isEqualTo(delay);
    }

    @Test
    void noPausesWhenTheModeIsOff() throws Exception {
        clockFollowsPauses();
        UUID id = UUID.randomUUID();

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());

        assertThat(sleeper.pauses()).isEmpty();
    }
}
