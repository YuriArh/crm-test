package com.acme.carrier.fault;

import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
