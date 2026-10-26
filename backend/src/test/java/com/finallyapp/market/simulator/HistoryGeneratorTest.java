package com.finallyapp.market.simulator;

import static com.finallyapp.market.testsupport.TestConfigs.simConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.DailyBar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryGeneratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);   // Monday

    private final SimulationParameters params = SimulationParameters.from(simConfig(1));

    @Test
    void barsAreConsistent() {
        var prof = new TickerProfiles(params).forTicker("AAPL");
        List<DailyBar> bars = new HistoryGenerator(params).backfill(prof, TODAY);
        assertThat(bars).hasSize(260);
        assertThat(bars.getLast().close()).isEqualByComparingTo("230.00");
        assertThat(bars).allSatisfy(b -> {
            assertThat(b.date().getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(b.low()).isLessThanOrEqualTo(b.open().min(b.close()));
            assertThat(b.high()).isGreaterThanOrEqualTo(b.open().max(b.close()));
            assertThat(b.low()).isPositive();
            assertThat(b.volume()).isPositive();
            assertThat(b.close().scale()).isEqualTo(4);
        });
    }

    @Test
    void datesAreStrictlyIncreasingAndEndOnLastWeekday() {
        var prof = new TickerProfiles(params).forTicker("MSFT");
        List<DailyBar> bars = new HistoryGenerator(params).backfill(prof, TODAY);
        assertThat(bars.getLast().date()).isEqualTo(LocalDate.of(2026, 10, 23));
        for (int i = 1; i < bars.size(); i++) {
            assertThat(bars.get(i).date()).isAfter(bars.get(i - 1).date());
        }
    }

    @Test
    void isDeterministicForSeedAndTicker() {
        var prof = new TickerProfiles(params).forTicker("NVDA");
        var gen = new HistoryGenerator(params);
        assertThat(gen.backfill(prof, TODAY)).isEqualTo(gen.backfill(prof, TODAY));

        var other = new HistoryGenerator(SimulationParameters.from(simConfig(2)));
        assertThat(other.backfill(prof, TODAY)).isNotEqualTo(gen.backfill(prof, TODAY));
    }

    @Test
    void differentTickersGetDifferentPaths() {
        var profiles = new TickerProfiles(params);
        var gen = new HistoryGenerator(params);
        var a = gen.backfill(profiles.forTicker("AAPL"), TODAY);
        var b = gen.backfill(profiles.forTicker("MSFT"), TODAY);
        assertThat(a.get(10).close().doubleValue() / a.getLast().close().doubleValue())
                .isNotEqualTo(b.get(10).close().doubleValue() / b.getLast().close().doubleValue());
    }

    @Test
    void tradingDaysSkipWeekends() {
        List<LocalDate> days = HistoryGenerator.tradingDaysBefore(TODAY, 6);
        assertThat(days).containsExactly(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));
    }

    @Test
    void isWeekday() {
        assertThat(HistoryGenerator.isWeekday(LocalDate.of(2026, 10, 23))).isTrue();
        assertThat(HistoryGenerator.isWeekday(LocalDate.of(2026, 10, 24))).isFalse();
        assertThat(HistoryGenerator.isWeekday(LocalDate.of(2026, 10, 25))).isFalse();
    }
}
