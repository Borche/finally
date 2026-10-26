package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finallyapp.market.spi.MarketDataSource;
import com.finallyapp.market.spi.PriceSink;
import com.finallyapp.market.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultMarketDataServiceTest {

    /** Source that publishes a fixed quote synchronously for every ticker it is given. */
    static final class FakeSource implements MarketDataSource {
        PriceSink sink;
        final Set<String> active = new HashSet<>();
        final List<Collection<String>> eodRequests = new ArrayList<>();
        boolean closed;
        int startCount;

        @Override public void start(Set<String> tickers, PriceSink sink) {
            this.sink = sink;
            startCount++;
            tickers.forEach(this::addTicker);
        }

        @Override public void addTicker(String ticker) {
            active.add(ticker);
            publish(ticker, "10.0000");
        }

        @Override public void removeTicker(String ticker) { active.remove(ticker); }

        void publish(String ticker, String price) {
            sink.publish(new PriceQuote(ticker, new BigDecimal(price), null, Instant.EPOCH, MarketDataMode.SIMULATED));
        }

        @Override public Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers) {
            eodRequests.add(List.copyOf(tickers));
            Map<String, DailyBar> out = new HashMap<>();
            for (String t : tickers) {
                if (t.equals("NOPE")) continue;
                out.put(t, new DailyBar(t, LocalDate.of(2026, 10, 23), BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.ONE, BigDecimal.TEN, 1));
            }
            return out;
        }

        @Override public MarketDataMode mode() { return MarketDataMode.SIMULATED; }

        @Override public void close() { closed = true; }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T15:00:00Z"));
    private FakeSource source;
    private DefaultMarketDataService service;

    @BeforeEach
    void setUp() {
        source = new FakeSource();
        service = new DefaultMarketDataService(source, List.of("aapl", "MSFT"), clock);
    }

    @Test
    void startHandsDefaultsToSourceAndMarksRunning() {
        assertThat(service.isRunning()).isFalse();
        service.start();
        assertThat(service.isRunning()).isTrue();
        assertThat(source.active).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(service.getAllPrices().keySet()).containsExactly("AAPL", "MSFT");
        assertThat(service.mode()).isEqualTo(MarketDataMode.SIMULATED);
    }

    @Test
    void stopClosesSource() {
        service.start();
        service.stop();
        assertThat(service.isRunning()).isFalse();
        assertThat(source.closed).isTrue();
    }

    @Test
    void readsNormaliseTickers() {
        service.start();
        assertThat(service.getPrice(" aapl ")).isPresent();
        assertThat(service.requirePrice("msft").ticker()).isEqualTo("MSFT");
        assertThat(service.getPrices(List.of("msft", "aapl", "zzz")).keySet()).containsExactly("MSFT", "AAPL");
    }

    @Test
    void trackedBeforeStartIsPassedToSourceOnStart() {
        service.track("nvda");
        assertThat(source.sink).isNull();
        service.start();
        assertThat(source.active).contains("NVDA");
    }

    @Test
    void trackIsIdempotentAndForwardedWhenRunning() {
        service.start();
        service.track("tsla");
        source.active.remove("TSLA");
        service.track("TSLA");                             // second call is a no-op
        assertThat(source.active).doesNotContain("TSLA");
        assertThat(service.getPrice("TSLA")).isPresent();
        assertThat(service.trackedTickers()).contains("TSLA");
    }

    @Test
    void untrackedTickerQuotesAreDroppedAndDefaultsStay() {
        service.start();
        service.track("tsla");
        assertThat(service.getPrice("TSLA")).isPresent();

        service.untrack("TSLA");
        service.untrack("AAPL");                           // default: ignored
        assertThat(service.trackedTickers()).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(source.active).doesNotContain("TSLA").contains("AAPL");
        assertThat(service.getPrice("TSLA")).isEmpty();

        source.publish("TSLA", "11.0000");                  // in-flight poll result after untrack
        assertThat(service.getPrice("TSLA")).isEmpty();
    }

    @Test
    void requirePriceThrowsForUnknown() {
        service.start();
        assertThatThrownBy(() -> service.requirePrice("TSLA")).isInstanceOf(UnknownTickerException.class);
    }

    @Test
    void invalidTickerIsRejected() {
        assertThatThrownBy(() -> service.getPrice("not a ticker")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.track("1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subscribersSeeUpdatesUntilClosed() {
        service.start();
        List<PriceQuote> got = new ArrayList<>();
        Subscription sub = service.subscribe(got::add);
        source.publish("AAPL", "12.0000");
        sub.close();
        source.publish("AAPL", "13.0000");
        assertThat(got).hasSize(1);
        assertThat(got.getFirst().price()).isEqualByComparingTo("12");
    }

    @Test
    void endOfDayIsCachedPerEtDate() {
        service.start();
        assertThat(service.getEndOfDay(List.of("aapl"))).containsOnlyKeys("AAPL");
        service.getEndOfDay(List.of("AAPL"));
        assertThat(source.eodRequests).hasSize(1);

        service.getEndOfDay(List.of("AAPL", "MSFT"));       // only the missing ticker is fetched
        assertThat(source.eodRequests.get(1)).containsExactly("MSFT");

        clock.set(Instant.parse("2026-10-27T15:00:00Z"));   // next ET date: cache invalidated
        service.getEndOfDay(List.of("AAPL"));
        assertThat(source.eodRequests).hasSize(3);
    }

    @Test
    void endOfDayOmitsTickersWithoutData() {
        service.start();
        assertThat(service.getEndOfDay(List.of("AAPL", "NOPE"))).containsOnlyKeys("AAPL");
    }
}
