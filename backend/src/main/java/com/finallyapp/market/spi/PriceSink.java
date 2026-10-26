package com.finallyapp.market.spi;

import com.finallyapp.market.PriceQuote;

@FunctionalInterface
public interface PriceSink {
    void publish(PriceQuote quote);
}
