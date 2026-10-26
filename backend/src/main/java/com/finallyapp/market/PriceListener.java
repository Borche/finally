package com.finallyapp.market;

@FunctionalInterface
public interface PriceListener {
    /** Called on a market-data thread. Must be fast and non-blocking; hand off slow work. */
    void onPrice(PriceQuote quote);
}
