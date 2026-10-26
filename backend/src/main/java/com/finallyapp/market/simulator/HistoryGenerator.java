package com.finallyapp.market.simulator;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.internal.Prices;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/** Pure and deterministic: same (seed, ticker, today) ⇒ same bars. */
final class HistoryGenerator {

    private static final int STEPS_PER_DAY = 78;                 // 6.5 h of 5-minute bars
    private static final double DAY = 1.0 / 252;

    private final SimulationParameters params;

    HistoryGenerator(SimulationParameters params) {
        this.params = params;
    }

    /** {@code historyDays} completed weekday sessions before {@code today}; last close == initialPrice. */
    List<DailyBar> backfill(TickerProfile prof, LocalDate today) {
        RandomGenerator rng = SimulationParameters.newRng(params.seed() ^ prof.ticker().hashCode());
        List<LocalDate> dates = tradingDaysBefore(today, params.historyDays());
        int n = dates.size();
        double mu = prof.annualDrift();
        double sigma = prof.annualVolatility();

        // 1. Closes, walking backwards: C[i-1] = C[i] / exp(daily log-return)
        double[] close = new double[n];
        close[n - 1] = prof.initialPrice();
        for (int i = n - 1; i > 0; i--) {
            double r = (mu - 0.5 * sigma * sigma) * DAY + sigma * Math.sqrt(DAY) * rng.nextGaussian();
            close[i - 1] = close[i] / Math.exp(r);
        }

        // 2. Intraday path per day, bridged from open to close.
        double subDt = DAY / STEPS_PER_DAY;
        double[] path = new double[STEPS_PER_DAY + 1];
        List<DailyBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double prevClose = i == 0 ? close[0] : close[i - 1];
            double open = prevClose * Math.exp(rng.nextGaussian() * 0.003);   // small overnight gap
            path[0] = Math.log(open);
            for (int k = 1; k <= STEPS_PER_DAY; k++) {
                path[k] = path[k - 1] + (mu - 0.5 * sigma * sigma) * subDt
                        + sigma * Math.sqrt(subDt) * rng.nextGaussian();
            }
            double error = Math.log(close[i]) - path[STEPS_PER_DAY];
            double high = open;
            double low = open;
            for (int k = 1; k <= STEPS_PER_DAY; k++) {
                double px = Math.exp(path[k] + error * k / STEPS_PER_DAY);
                high = Math.max(high, px);
                low = Math.min(low, px);
            }
            // Rounded values must also satisfy low <= open,close <= high.
            high = Math.max(high, Math.max(open, close[i]));
            low = Math.min(low, Math.min(open, close[i]));
            long volume = Math.round(prof.averageVolume() * Math.exp(0.3 * rng.nextGaussian() - 0.045));
            bars.add(new DailyBar(prof.ticker(), dates.get(i), Prices.toTick(open), Prices.toTick(high),
                    Prices.toTick(low), Prices.toTick(close[i]), volume));
        }
        return bars;
    }

    /** Oldest → newest weekdays strictly before {@code today}. Holidays are ignored. */
    static List<LocalDate> tradingDaysBefore(LocalDate today, int count) {
        Deque<LocalDate> out = new ArrayDeque<>(count);
        for (LocalDate d = today.minusDays(1); out.size() < count; d = d.minusDays(1)) {
            if (isWeekday(d)) out.addFirst(d);
        }
        return List.copyOf(out);
    }

    static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
