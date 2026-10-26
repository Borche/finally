package com.finallyapp.market.simulator;

import static com.finallyapp.market.testsupport.TestConfigs.simConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.spi.MarketDataSource;
import com.finallyapp.market.testsupport.MarketDataSourceContractTest;
import com.finallyapp.market.testsupport.MutableClock;
import com.finallyapp.market.testsupport.RecordingSink;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatedMarketDataSourceTest extends MarketDataSourceContractTest {

    // Monday 2026-10-26 15:00 UTC = 11:00 ET
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T15:00:00Z"));
    private SimulatedMarketDataSource sim;

    @Override
    protected MarketDataSource newSource() {
        sim = new SimulatedMarketDataSource(simConfig(42), clock, false);
        return sim;
    }

    @Override
    protected void advance() {
        sim.tick();
    }

    @Test
    void modeIsSimulated() {
        assertThat(sim.mode()).isEqualTo(MarketDataMode.SIMULATED);
    }

    @Test
    void sameSeedGivesIdenticalSequence() {
        List<PriceQuote> first = run(42);
        List<PriceQuote> second = run(42);
        assertThat(first).isNotEmpty().isEqualTo(second);
        assertThat(run(43)).isNotEqualTo(first);
    }

    private List<PriceQuote> run(long seed) {
        var s = new SimulatedMarketDataSource(simConfig(seed), new MutableClock(clock.instant()), false);
        var rec = new RecordingSink();
        s.start(Set.of("AAPL", "MSFT", "ZZZZ"), rec);
        for (int i = 0; i < 1_000; i++) s.tick();
        s.close();
        return List.copyOf(rec.all);
    }

    @Test
    void startPublishesWithNonZeroChange() {
        sim.start(Set.of("AAPL"), sink);
        PriceQuote q = sink.latest.get("AAPL");
        assertThat(q.previousClose()).isEqualByComparingTo("230.0000");
        assertThat(q.price()).isNotEqualByComparingTo(q.previousClose());   // overnight gap
    }

    @Test
    void startPublishesExactlyOneQuotePerTicker() {
        sim.start(Set.of("AAPL", "MSFT", "NVDA"), sink);
        assertThat(sink.all).hasSize(3);
    }

    @Test
    void unknownTickerGetsStablePositivePrice() {
        sim.start(Set.of("ZZZZ"), sink);
        BigDecimal price = sink.latest.get("ZZZZ").previousClose();
        assertThat(price).isBetween(new BigDecimal("10"), new BigDecimal("500"));
    }

    @Test
    void weekdayRolloverMovesPreviousCloseAndAppendsBar() {
        sim.start(Set.of("AAPL"), sink);
        for (int i = 0; i < 100; i++) sim.tick();
        BigDecimal lastPrice = sink.latest.get("AAPL").price();

        clock.set(Instant.parse("2026-10-27T04:30:00Z"));                   // 00:30 ET Tuesday
        sim.tick();

        DailyBar monday = sim.fetchEndOfDay(List.of("AAPL")).get("AAPL");
        assertThat(monday.date()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(monday.close()).isEqualByComparingTo(lastPrice);
        assertThat(monday.low()).isLessThanOrEqualTo(monday.open().min(monday.close()));
        assertThat(monday.high()).isGreaterThanOrEqualTo(monday.open().max(monday.close()));
        assertThat(sink.latest.get("AAPL").previousClose()).isEqualByComparingTo(lastPrice);
    }

    @Test
    void weekendDoesNotCloseASession() {
        sim.start(Set.of("AAPL"), sink);
        clock.set(Instant.parse("2026-10-31T16:00:00Z"));                   // Saturday ET
        sim.tick();                                                         // closes Monday's session
        DailyBar afterRoll = sim.fetchEndOfDay(List.of("AAPL")).get("AAPL");

        clock.set(Instant.parse("2026-11-01T16:00:00Z"));                   // Sunday ET: Saturday must not close
        sim.tick();
        assertThat(sim.fetchEndOfDay(List.of("AAPL")).get("AAPL")).isEqualTo(afterRoll);
        assertThat(afterRoll.date().getDayOfWeek()).isNotIn(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY);
    }

    @Test
    void historyEndsOnLastWeekdayAndMatchesPreviousClose() {
        sim.start(Set.of("AAPL"), sink);
        DailyBar bar = sim.fetchEndOfDay(List.of("AAPL")).get("AAPL");
        assertThat(bar.date()).isEqualTo(LocalDate.of(2026, 10, 23));      // Friday
        assertThat(bar.close()).isEqualByComparingTo(sink.latest.get("AAPL").previousClose());
    }

    @Test
    void endOfDayForUntrackedTickerMatchesLaterTracking() {
        sim.start(Set.of("AAPL"), sink);
        DailyBar before = sim.fetchEndOfDay(List.of("NVDA")).get("NVDA");
        sim.addTicker("NVDA");
        assertThat(sim.fetchEndOfDay(List.of("NVDA")).get("NVDA")).isEqualTo(before);
    }

    @Test
    void addTickerTwiceKeepsOneState() {
        sim.start(Set.of("AAPL"), sink);
        sim.addTicker("NVDA");
        int quotes = sink.all.size();
        sim.addTicker("NVDA");
        assertThat(sink.all).hasSize(quotes);
    }

    @Test
    void autoStartSourceTicksOnItsOwnThread() throws Exception {
        var fast = new com.finallyapp.market.config.MarketProperties.Simulator(
                java.time.Duration.ofMillis(10), 1L, 60.0, 0.5, 0.4, java.time.Duration.ofHours(2),
                0.02, 0.015, 30);
        var real = new SimulatedMarketDataSource(fast);
        var rec = new RecordingSink();
        try {
            real.start(Set.of("AAPL"), rec);
            long deadline = System.currentTimeMillis() + 5_000;
            while (rec.all.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertThat(rec.all.size()).isGreaterThanOrEqualTo(3);
        } finally {
            real.close();
        }
    }
}
