package com.finallyapp.market.massive;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.massive.dto.Bar;
import com.finallyapp.market.massive.dto.LastTrade;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotPricesTest {

    private static final Instant FALLBACK = Instant.parse("2026-10-26T15:00:00Z");

    private static Bar bar(Double close) {
        return new Bar(null, null, null, close, null, null, null);
    }

    private static TickerSnapshot snap(Long updated, Bar day, Bar min, Bar prevDay, LastTrade trade) {
        return new TickerSnapshot("AAPL", null, null, updated, day, min, prevDay, trade);
    }

    @Test
    void prefersLastTrade() {
        var s = snap(null, bar(3.0), bar(2.0), bar(1.0), new LastTrade(4.0, null, null));
        assertThat(SnapshotPrices.currentPrice(s)).contains(4.0);
    }

    @Test
    void fallsBackThroughMinDayPrevDay() {
        assertThat(SnapshotPrices.currentPrice(snap(null, bar(3.0), bar(2.0), bar(1.0), null))).contains(2.0);
        assertThat(SnapshotPrices.currentPrice(snap(null, bar(3.0), null, bar(1.0), null))).contains(3.0);
        assertThat(SnapshotPrices.currentPrice(snap(null, null, null, bar(1.0), null))).contains(1.0);
    }

    @Test
    void skipsZeroAndNullValues() {
        var s = snap(null, bar(0.0), bar(0.0), bar(5.5), new LastTrade(0.0, null, null));
        assertThat(SnapshotPrices.currentPrice(s)).contains(5.5);
        assertThat(SnapshotPrices.currentPrice(snap(null, bar(null), null, null, new LastTrade(null, null, null))))
                .isEqualTo(Optional.empty());
    }

    @Test
    void noPriceMeansNoQuote() {
        assertThat(SnapshotPrices.toQuote(snap(null, null, null, null, null), FALLBACK)).isEmpty();
    }

    @Test
    void asOfPrefersUpdatedThenLastTradeThenMinThenFallback() {
        long nanos = 1_792_940_400_123_456_789L;
        assertThat(SnapshotPrices.asOf(snap(nanos, null, null, null, null), FALLBACK))
                .isEqualTo(Instant.ofEpochSecond(1_792_940_400L, 123_456_789L));

        var trade = new LastTrade(1.0, null, 1_792_940_000_000_000_000L);
        assertThat(SnapshotPrices.asOf(snap(0L, null, null, null, trade), FALLBACK))
                .isEqualTo(Instant.ofEpochSecond(1_792_940_000L));

        var min = new Bar(null, null, null, 1.0, null, null, 1_792_939_000_000L);
        assertThat(SnapshotPrices.asOf(snap(null, null, min, null, null), FALLBACK))
                .isEqualTo(Instant.ofEpochMilli(1_792_939_000_000L));

        assertThat(SnapshotPrices.asOf(snap(null, null, null, null, null), FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void toQuoteBuildsScaleFourPricesAndPreviousClose() {
        var s = snap(1_792_940_400_000_000_000L, null, bar(231.4), bar(229.87), null);
        PriceQuote q = SnapshotPrices.toQuote(s, FALLBACK).orElseThrow();
        assertThat(q.ticker()).isEqualTo("AAPL");
        assertThat(q.price().toPlainString()).isEqualTo("231.4000");
        assertThat(q.previousClose().toPlainString()).isEqualTo("229.8700");
        assertThat(q.mode()).isEqualTo(MarketDataMode.MASSIVE_LIVE);
    }

    @Test
    void missingOrZeroPrevDayGivesNullPreviousClose() {
        assertThat(SnapshotPrices.toQuote(snap(null, null, bar(10.0), null, null), FALLBACK).orElseThrow()
                .previousClose()).isNull();
        assertThat(SnapshotPrices.toQuote(snap(null, null, bar(10.0), bar(0.0), null), FALLBACK).orElseThrow()
                .previousClose()).isNull();
    }
}
