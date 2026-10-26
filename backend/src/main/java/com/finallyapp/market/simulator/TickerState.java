package com.finallyapp.market.simulator;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.internal.Prices;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Mutable per-ticker state. Written only on the simulator thread; lastCompletedBar is safe to read anywhere. */
final class TickerState {

    private final TickerProfile profile;
    private final List<DailyBar> history;          // oldest → newest, completed sessions only
    private double price;                          // unrounded model state
    private BigDecimal publishedPrice;             // last rounded price
    private BigDecimal previousClose;
    private double dayOpen;
    private double dayHigh;
    private double dayLow;
    private volatile DailyBar lastCompletedBar;

    TickerState(TickerProfile profile, List<DailyBar> history) {
        this.profile = profile;
        this.history = new ArrayList<>(history);
        this.lastCompletedBar = history.getLast();
        this.previousClose = lastCompletedBar.close();
        this.price = previousClose.doubleValue();
        this.publishedPrice = previousClose;
        startDay();
    }

    TickerProfile profile() { return profile; }

    double price() { return price; }

    DailyBar lastCompletedBar() { return lastCompletedBar; }

    int historySize() { return history.size(); }

    /** First live price is offset from the previous close by N(0, 0.5 %) so the UI shows a change at once. */
    void applyOvernightGap(RandomGenerator rng) {
        price *= Math.exp(rng.nextGaussian() * 0.005);
        publishedPrice = Prices.toTick(price);
        startDay();
    }

    /** Apply a model step; returns true if the rounded (published) price changed. */
    boolean update(double next) {
        price = next;
        dayHigh = Math.max(dayHigh, next);
        dayLow = Math.min(dayLow, next);
        BigDecimal rounded = Prices.toTick(next);
        if (rounded.equals(publishedPrice)) return false;
        publishedPrice = rounded;
        return true;
    }

    PriceQuote toQuote(Instant asOf) {
        return new PriceQuote(profile.ticker(), publishedPrice, previousClose, asOf, MarketDataMode.SIMULATED);
    }

    /** End of session {@code day}: record its bar and make the current price the new previous close. */
    void closeDay(LocalDate day, RandomGenerator rng) {
        long volume = Math.round(profile.averageVolume() * Math.exp(0.3 * rng.nextGaussian() - 0.045));
        BigDecimal open = Prices.toTick(dayOpen);
        BigDecimal high = Prices.toTick(dayHigh).max(open).max(publishedPrice);
        BigDecimal low = Prices.toTick(dayLow).min(open).min(publishedPrice);
        DailyBar bar = new DailyBar(profile.ticker(), day, open, high, low, publishedPrice, volume);
        history.add(bar);
        lastCompletedBar = bar;
        previousClose = publishedPrice;
        startDay();
    }

    private void startDay() {
        dayOpen = price;
        dayHigh = price;
        dayLow = price;
    }
}
