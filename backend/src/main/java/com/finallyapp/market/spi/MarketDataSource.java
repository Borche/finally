package com.finallyapp.market.spi;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** A producer of prices. Exactly one is active per application. Tickers passed in are already normalised. */
public interface MarketDataSource extends AutoCloseable {

    /** Begin producing quotes for {@code tickers}; publish each one to {@code sink}. Called once. */
    void start(Set<String> tickers, PriceSink sink);

    /** Called only after {@link #start}. */
    void addTicker(String ticker);

    void removeTicker(String ticker);

    /** Last completed session per ticker. May block on I/O. Thread-safe. */
    Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers);

    /** Current mode; may change at runtime. */
    MarketDataMode mode();

    /** Stop threads. Must be idempotent (Spring may call it twice). */
    @Override
    void close();
}
