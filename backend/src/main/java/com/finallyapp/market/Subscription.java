package com.finallyapp.market;

/** Handle returned by {@link MarketDataService#subscribe}. Closing it is idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
