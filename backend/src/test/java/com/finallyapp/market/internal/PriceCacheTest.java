package com.finallyapp.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.Subscription;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCacheTest {

    private final PriceCache cache = new PriceCache();
    private final PriceQuote q = quote("AAPL", "1.0000");

    private static PriceQuote quote(String ticker, String price) {
        return new PriceQuote(ticker, new BigDecimal(price), null, Instant.EPOCH, MarketDataMode.SIMULATED);
    }

    @Test
    void throwingListenerDoesNotBlockOthers() {
        List<PriceQuote> got = new ArrayList<>();
        cache.addListener(x -> { throw new IllegalStateException("boom"); });
        cache.addListener(got::add);
        cache.publish(q);
        assertThat(got).containsExactly(q);
    }

    @Test
    void duplicatesAreSuppressedAndUnsubscribeWorks() {
        List<PriceQuote> got = new ArrayList<>();
        Subscription sub = cache.addListener(got::add);
        cache.publish(q);
        cache.publish(q);
        sub.close();
        sub.close();                                         // idempotent
        cache.publish(quote("AAPL", "2.0000"));
        assertThat(got).hasSize(1);
    }

    @Test
    void getAndRemove() {
        cache.publish(q);
        assertThat(cache.get("AAPL")).contains(q);
        cache.remove("AAPL");
        assertThat(cache.get("AAPL")).isEmpty();
    }

    @Test
    void snapshotIsSortedAndUnmodifiable() {
        cache.publish(quote("MSFT", "2.0000"));
        cache.publish(quote("AAPL", "1.0000"));
        assertThat(cache.snapshot().keySet()).containsExactly("AAPL", "MSFT");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.snapshot().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changedPriceNotifiesAgain() {
        List<PriceQuote> got = new ArrayList<>();
        cache.addListener(got::add);
        cache.publish(quote("AAPL", "1.0000"));
        cache.publish(quote("AAPL", "1.0100"));
        assertThat(got).hasSize(2);
    }
}
