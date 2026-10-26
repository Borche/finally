package com.finallyapp.market.testsupport;

import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.spi.PriceSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Records everything a source publishes. */
public final class RecordingSink implements PriceSink {
    public final List<PriceQuote> all = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, PriceQuote> latest = new ConcurrentHashMap<>();

    @Override
    public void publish(PriceQuote q) {
        all.add(q);
        latest.put(q.ticker(), q);
    }

    public void clear() {
        all.clear();
        latest.clear();
    }
}
