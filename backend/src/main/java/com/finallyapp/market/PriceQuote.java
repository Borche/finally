package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Latest known price for one ticker. Prices are BigDecimal with scale 4.
 *
 * @param previousClose close of the previous session, or null if unknown
 * @param asOf          when the price was observed at the source (not when we fetched it)
 */
public record PriceQuote(
        String ticker,
        BigDecimal price,
        BigDecimal previousClose,
        Instant asOf,
        MarketDataMode mode) {

    public BigDecimal change() {
        return previousClose == null ? BigDecimal.ZERO : price.subtract(previousClose);
    }

    public BigDecimal changePercent() {
        if (previousClose == null || previousClose.signum() == 0) return BigDecimal.ZERO;
        return change().multiply(BigDecimal.valueOf(100)).divide(previousClose, 2, RoundingMode.HALF_UP);
    }
}
