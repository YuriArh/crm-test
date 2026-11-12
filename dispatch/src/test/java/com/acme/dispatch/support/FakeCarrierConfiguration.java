package com.acme.dispatch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Integration tests talk to {@link FakeCarrier} instead of a real carrier over HTTP. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeCarrierConfiguration {

    @Bean
    @Primary
    FakeCarrier fakeCarrier() {
        return new FakeCarrier();
    }
}
