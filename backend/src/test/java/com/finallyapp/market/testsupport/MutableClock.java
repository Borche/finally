package com.finallyapp.market.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Clock whose time tests advance by hand. */
public final class MutableClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) { this(start, ZoneOffset.UTC); }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration d) { now = now.plus(d); }

    public void set(Instant instant) { now = instant; }

    @Override public Instant instant() { return now; }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId z) { return new DelegatingZoneClock(this, z); }

    /** Shares the parent's time, so advancing the parent moves zone views too. */
    private static final class DelegatingZoneClock extends Clock {
        private final MutableClock parent;
        private final ZoneId zone;

        DelegatingZoneClock(MutableClock parent, ZoneId zone) {
            this.parent = parent;
            this.zone = zone;
        }

        @Override public Instant instant() { return parent.instant(); }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId z) { return new DelegatingZoneClock(parent, z); }
    }
}
