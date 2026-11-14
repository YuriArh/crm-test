package com.acme.carrier.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeSleeperConfiguration {

    @Bean
    @Primary
    FakeSleeper fakeSleeper() {
        return new FakeSleeper();
    }
}
