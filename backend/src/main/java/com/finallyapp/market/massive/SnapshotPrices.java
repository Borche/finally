package com.finallyapp.market.massive;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.internal.Prices;
import com.finallyapp.market.massive.dto.Bar;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

final class SnapshotPrices {

    private SnapshotPrices() {}

    /** lastTrade.p → min.c → day.c → prevDay.c, skipping missing/zero (snapshot resets ~3:30 AM ET). */
    static Optional<Double> currentPrice(TickerSnapshot s) {
        return Stream.of(
                        s.lastTrade() == null ? null : s.lastTrade().price(),
                        close(s.min()),
                        close(s.day()),
                        close(s.prevDay()))
                .filter(p -> p != null && p > 0)
                .findFirst();
    }

    /** updated (ns) → lastTrade.t (ns) → min.t (ms) → fallback. */
    static Instant asOf(TickerSnapshot s, Instant fallback) {
        if (s.updated() != null && s.updated() > 0) return Instant.ofEpochSecond(0, s.updated());
        if (s.lastTrade() != null && s.lastTrade().timestampNanos() != null && s.lastTrade().timestampNanos() > 0) {
            return Instant.ofEpochSecond(0, s.lastTrade().timestampNanos());
        }
        if (s.min() != null && s.min().timestampMillis() != null && s.min().timestampMillis() > 0) {
            return Instant.ofEpochMilli(s.min().timestampMillis());
        }
        return fallback;
    }

    static Optional<PriceQuote> toQuote(TickerSnapshot s, Instant fallback) {
        Double prev = close(s.prevDay());
        return currentPrice(s).map(p -> new PriceQuote(
                s.ticker(),
                Prices.of(p),
                prev == null || prev <= 0 ? null : Prices.of(prev),
                asOf(s, fallback),
                MarketDataMode.MASSIVE_LIVE));
    }

    private static Double close(Bar b) {
        return b == null ? null : b.close();
    }
}
