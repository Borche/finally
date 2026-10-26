package com.finallyapp.market.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finallyapp.market.spi.MarketDataSource;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour both MarketDataSource implementations must satisfy. Sources run in manual mode. */
public abstract class MarketDataSourceContractTest {

    protected RecordingSink sink = new RecordingSink();
    protected MarketDataSource source;

    /** A source in manual mode. */
    protected abstract MarketDataSource newSource();

    /** Make the source produce one round of quotes. */
    protected abstract void advance();

    @BeforeEach
    public void setUp() {
        source = newSource();
    }

    @AfterEach
    public void tearDown() {
        source.close();
    }

    @Test
    public void publishesAQuoteForEveryStartTicker() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        assertThat(sink.latest).containsOnlyKeys("AAPL", "MSFT");
    }

    @Test
    public void addedTickerGetsAQuote() {
        source.start(Set.of("AAPL"), sink);
        source.addTicker("NVDA");
        advance();
        assertThat(sink.latest).containsKey("NVDA");
    }

    @Test
    public void removedTickerStopsPublishing() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        source.removeTicker("MSFT");
        sink.clear();
        for (int i = 0; i < 20; i++) advance();
        assertThat(sink.all).noneMatch(q -> q.ticker().equals("MSFT"));
    }

    @Test
    public void quotesAreWellFormed() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        assertThat(sink.all).isNotEmpty().allSatisfy(q -> {
            assertThat(q.price()).isPositive();
            assertThat(q.price().scale()).isEqualTo(4);
            assertThat(q.ticker()).isUpperCase();
            assertThat(q.asOf()).isNotNull();
            assertThat(q.mode()).isEqualTo(source.mode());
        });
    }

    @Test
    public void closeIsIdempotent() {
        source.start(Set.of("AAPL"), sink);
        source.close();
        assertThatCode(source::close).doesNotThrowAnyException();
    }
}
