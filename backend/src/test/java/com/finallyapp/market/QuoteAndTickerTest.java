package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finallyapp.market.internal.Prices;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuoteAndTickerTest {

    private static PriceQuote quote(String price, String prev) {
        return new PriceQuote("AAPL", new BigDecimal(price), prev == null ? null : new BigDecimal(prev),
                Instant.EPOCH, MarketDataMode.SIMULATED);
    }

    @Test
    void changeAndPercent() {
        PriceQuote q = quote("110.0000", "100.0000");
        assertThat(q.change()).isEqualByComparingTo("10");
        assertThat(q.changePercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void negativeChange() {
        assertThat(quote("99.0000", "100.0000").changePercent()).isEqualByComparingTo("-1.00");
    }

    @Test
    void unknownOrZeroPreviousCloseGivesZeroChange() {
        assertThat(quote("10.0000", null).change()).isEqualByComparingTo("0");
        assertThat(quote("10.0000", null).changePercent()).isEqualByComparingTo("0");
        assertThat(quote("10.0000", "0").changePercent()).isEqualByComparingTo("0");
    }

    @Test
    void modeLabels() {
        assertThat(MarketDataMode.SIMULATED.label()).isEqualTo("Simulated");
        assertThat(MarketDataMode.MASSIVE_LIVE.label()).isEqualTo("Live");
        assertThat(MarketDataMode.MASSIVE_EOD.label()).isEqualTo("End of day");
    }

    @Test
    void normalizeTrimsAndUppercases() {
        assertThat(Tickers.normalize("  aapl ")).isEqualTo("AAPL");
        assertThat(Tickers.normalize("brk.b")).isEqualTo("BRK.B");
        assertThat(Tickers.normalize("bf-b")).isEqualTo("BF-B");
    }

    @Test
    void normalizeRejectsInvalid() {
        assertThatThrownBy(() -> Tickers.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tickers.normalize("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tickers.normalize("1ABC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tickers.normalize("zzzz!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tickers.normalize("ABCDEFGHIJK")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pricesHaveScaleFour() {
        assertThat(Prices.of(231.4)).isEqualTo(new BigDecimal("231.4000"));
        assertThat(Prices.of(0.12345)).isEqualTo(new BigDecimal("0.1235"));
    }

    @Test
    void toTickRoundsToCentsAboveOneDollarAndFourPlacesBelow() {
        assertThat(Prices.toTick(231.456)).isEqualTo(new BigDecimal("231.4600"));
        assertThat(Prices.toTick(1.0)).isEqualTo(new BigDecimal("1.0000"));
        assertThat(Prices.toTick(0.123456)).isEqualTo(new BigDecimal("0.1235"));
    }

    @Test
    void unknownTickerExceptionCarriesTicker() {
        var e = new UnknownTickerException("ZZZ");
        assertThat(e.ticker()).isEqualTo("ZZZ");
        assertThat(e).hasMessageContaining("ZZZ");
    }
}
