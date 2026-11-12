package com.acme.dispatch;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DispatchApplication {

    public static void main(String[] args) {
        // All instants are stored and processed in UTC; the business time zone
        // (app.timezone) is applied explicitly where local dates matter.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(DispatchApplication.class, args);
    }
}
