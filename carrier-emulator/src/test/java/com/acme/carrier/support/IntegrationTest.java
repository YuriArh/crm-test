package com.acme.carrier.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Integration test against a real PostgreSQL (Testcontainers) with MockMvc, a {@link MutableClock} and a
 * {@link FakeSleeper} (no real pauses). The background executor is off: tests run it with
 * {@code Executor.runOnce()}. Every integration test uses exactly this annotation, so they share one Spring
 * context and one container; reset shared state with {@link TestRows#resetState}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "carrier.executor-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, FakeSleeperConfiguration.class})
public @interface IntegrationTest {
}
