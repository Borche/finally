package com.finallyapp.market.internal;

import com.finallyapp.market.PriceListener;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.Subscription;
import com.finallyapp.market.spi.PriceSink;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Latest quote per ticker plus listener fan-out. Lock-free reads; publish is called from one source thread. */
public final class PriceCache implements PriceSink {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final ConcurrentHashMap<String, PriceQuote> latest = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PriceListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(PriceQuote quote) {
        PriceQuote previous = latest.put(quote.ticker(), quote);
        if (quote.equals(previous)) return;                 // identical re-poll: no notification
        for (PriceListener l : listeners) {
            try {
                l.onPrice(quote);
            } catch (RuntimeException e) {                  // one bad listener must not break the feed
                log.warn("Price listener failed for {}", quote.ticker(), e);
            }
        }
    }

    public Optional<PriceQuote> get(String ticker) {
        return Optional.ofNullable(latest.get(ticker));
    }

    public Map<String, PriceQuote> snapshot() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(latest));
    }

    public void remove(String ticker) {
        latest.remove(ticker);
    }

    public Subscription addListener(PriceListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
