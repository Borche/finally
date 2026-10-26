package com.finallyapp.market.simulator;

import static com.finallyapp.market.testsupport.TestConfigs.simConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TickerProfilesTest {

    private final SimulationParameters params = SimulationParameters.from(simConfig(1));

    @Test
    void unknownTickerIsStable() {
        assertThat(TickerProfiles.synthesize("ZZZZ", params)).isEqualTo(TickerProfiles.synthesize("ZZZZ", params));
        assertThat(TickerProfiles.synthesize("ZZZZ", params).initialPrice()).isBetween(10.0, 500.0);
    }

    @Test
    void unknownTickersDiffer() {
        assertThat(TickerProfiles.synthesize("ZZZZ", params)).isNotEqualTo(TickerProfiles.synthesize("YYYY", params));
    }

    @Test
    void synthesizedProfilesAreInRange() {
        for (String t : new String[] {"A", "QWER", "FOO", "BAR", "XYZ1", "MMM"}) {
            TickerProfile p = TickerProfiles.synthesize(t, params);
            assertThat(p.annualVolatility()).isBetween(0.20, 0.60);
            assertThat(p.sector()).isIn(Sector.STOCK_SECTORS);
            assertThat(p.marketBeta()).isEqualTo(0.5);
            assertThat(p.averageVolume()).isPositive();
        }
    }

    @Test
    void builtInProfilesAreLoadedFromCsv() {
        var profiles = new TickerProfiles(params);
        TickerProfile aapl = profiles.forTicker("AAPL");
        assertThat(aapl.initialPrice()).isEqualTo(230.0);
        assertThat(aapl.annualVolatility()).isEqualTo(0.25);
        assertThat(aapl.sector()).isEqualTo(Sector.TECH);
        assertThat(aapl.marketBeta()).isEqualTo(0.5);
        assertThat(aapl.sectorBeta()).isEqualTo(0.4);
    }

    @Test
    void indexEtfsTrackTheMarketFactor() {
        TickerProfile spy = new TickerProfiles(params).forTicker("SPY");
        assertThat(spy.sector()).isEqualTo(Sector.INDEX);
        assertThat(spy.marketBeta()).isEqualTo(0.95);
        assertThat(spy.sectorBeta()).isEqualTo(0.0);
    }

    @Test
    void allCsvTickersPresent() {
        var profiles = new TickerProfiles(params);
        for (String t : new String[] {"AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "JPM", "V", "JNJ",
                "XOM", "SPY", "QQQ"}) {
            assertThat(profiles.forTicker(t).initialPrice()).isPositive();
            assertThat(profiles.forTicker(t)).isNotEqualTo(TickerProfiles.synthesize(t, params));
        }
    }

    @Test
    void profileRejectsInvalidBetas() {
        assertThatThrownBy(() -> new TickerProfile("X", 1, 0, 0.2, Sector.TECH, 0.9, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
