package com.acme.carrier.fault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UnavailableFilterTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /operations, true",
            "GET, /operations/0196f0a8-0000-7000-8000-000000000001, true",
            "GET, /results, true",
            "GET, /operations, false",
            "GET, /operations/, false",
            "GET, /operations/x/y, false",
            "PUT, /operations/x, false",
            "POST, /results, false",
            "GET, /admin/operations, false",
            "GET, /admin/faults, false",
            "PUT, /admin/faults, false",
            "GET, /actuator/health, false",
    })
    void onlyExchangeEndpointsAreAffected(String method, String path, boolean affected) {
        assertThat(UnavailableFilter.isExchangeEndpoint(method, path)).isEqualTo(affected);
    }
}
