package com.acme.dispatch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** Every MockMvc response of an integration test must match the dispatch contract. */
@TestConfiguration(proxyBeanMethods = false)
public class ContractConfiguration {

    @Bean
    MockMvcBuilderCustomizer contractCheck() {
        return builder -> builder.alwaysExpect(Contract.matchesContract());
    }
}
