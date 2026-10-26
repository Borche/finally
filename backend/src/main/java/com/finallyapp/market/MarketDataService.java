package com.finallyapp.market;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MarketDataService {

    /** Latest cached quote; empty if the ticker is not tracked or has no price yet. Never blocks on I/O. */
    Optional<PriceQuote> getPrice(String ticker);

    /** Like {@link #getPrice} but throws {@link UnknownTickerException}. Use for trade execution. */
    PriceQuote requirePrice(String ticker);

    /** Latest cached quotes for the given tickers, in request order; tickers without a price are omitted. */
    Map<String, PriceQuote> getPrices(Collection<String> tickers);

    /** Snapshot of every tracked ticker that has a price, sorted by ticker. */
    Map<String, PriceQuote> getAllPrices();

    /** Last completed session per ticker. May do I/O on a cache miss (cached per ET date). Not for hot paths. */
    Map<String, DailyBar> getEndOfDay(Collection<String> tickers);

    /** Start producing prices for a ticker (idempotent). The first quote arrives asynchronously. */
    void track(String ticker);

    /** Stop producing prices for a ticker. Default tickers are never untracked. */
    void untrack(String ticker);

    Set<String> trackedTickers();

    /** Register for every published quote. Close the returned subscription to stop. */
    Subscription subscribe(PriceListener listener);

    /** Which backend is active (may change at runtime from MASSIVE_LIVE to MASSIVE_EOD). */
    MarketDataMode mode();
}
