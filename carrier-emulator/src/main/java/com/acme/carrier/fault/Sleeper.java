package com.acme.carrier.fault;

import java.time.Duration;

/** Pauses the current thread; replaced in integration tests so they never wait for real. */
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
