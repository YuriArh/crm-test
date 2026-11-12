package com.acme.carrier;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Emulator of the external delivery carrier; contract: {@code contracts/carrier-api.yaml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CarrierEmulatorApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CarrierEmulatorApplication.class, args);
    }
}
