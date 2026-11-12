package com.acme.dispatch.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.acme.dispatch.TestcontainersConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Integration test against a real PostgreSQL (Testcontainers) with MockMvc and the test clock.
 * The carrier is a {@link FakeCarrier}; the background exchange workers are off, tests call their
 * {@code runOnce()} directly.
 * Every integration test uses this exact annotation so they all share one Spring context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "app.exchange.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, FakeCarrierConfiguration.class,
        TestRequests.class})
public @interface IntegrationTest {
}
