package com.finallyapp.market.massive;

import java.time.Duration;

/** Indirection over Thread.sleep so rate-limit spacing is testable without waiting. */
@FunctionalInterface
interface Sleeper {
    void sleep(Duration d) throws InterruptedException;

    Sleeper REAL = d -> Thread.sleep(d.toMillis());
}
