package com.acme.dispatch.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.acme.dispatch.TestcontainersConfiguration;
import com.acme.dispatch.exchange.inbox.InboxDao;
import com.acme.dispatch.problem.ProblemDao;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Integration test against a real PostgreSQL (Testcontainers) with MockMvc and the test clock.
 * The carrier is a {@link FakeCarrier}; the background exchange workers are off, tests call their
 * {@code runOnce()} directly.
 * {@link InboxDao} and {@link ProblemDao} are Mockito spies: they behave as the real ones unless a test makes them
 * fail, so failures while processing a result or recording a problem are tested without anything test-specific in
 * the production code. Spies are reset after each test.
 * Every integration test uses this exact annotation so they all share one Spring context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "app.exchange.enabled=false")
@AutoConfigureMockMvc
@MockitoSpyBean(types = {InboxDao.class, ProblemDao.class})
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, FakeCarrierConfiguration.class,
        TestRequests.class, TestResults.class, ContractConfiguration.class})
public @interface IntegrationTest {
}
