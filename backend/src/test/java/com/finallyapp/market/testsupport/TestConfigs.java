package com.finallyapp.market.testsupport;

import com.finallyapp.market.config.MarketProperties;
import java.time.Duration;

public final class TestConfigs {

    private TestConfigs() {}

    public static MarketProperties.Simulator simConfig(long seed) {
        return new MarketProperties.Simulator(Duration.ofMillis(500), seed, 1.0, 0.5, 0.4,
                Duration.ofHours(2), 0.02, 0.015, 260);
    }
}
