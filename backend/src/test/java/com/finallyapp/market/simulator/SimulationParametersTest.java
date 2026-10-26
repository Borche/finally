package com.finallyapp.market.simulator;

import static com.finallyapp.market.testsupport.TestConfigs.simConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.finallyapp.market.config.MarketProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulationParametersTest {

    @Test
    void dtIsTradingTimeFraction() {
        var p = SimulationParameters.from(simConfig(1));
        assertThat(p.dt()).isCloseTo(8.48e-8, within(0.01e-8));      // 500 ms / (252 * 6.5 h)
    }

    @Test
    void timeScaleScalesDtAndJumpRates() {
        var base = SimulationParameters.from(simConfig(1));
        var scaled = SimulationParameters.from(new MarketProperties.Simulator(Duration.ofMillis(500), 1L, 60.0, 0.5,
                0.4, Duration.ofHours(2), 0.02, 0.015, 260));
        assertThat(scaled.dt()).isCloseTo(base.dt() * 60, within(1e-12));
        assertThat(scaled.jumpProbabilityPerTick()).isCloseTo(base.jumpProbabilityPerTick() * 60, within(1e-9));
    }

    @Test
    void jumpProbabilityIsTickOverMeanInterval() {
        var p = SimulationParameters.from(simConfig(1));
        assertThat(p.jumpProbabilityPerTick()).isCloseTo(0.5 / 7200, within(1e-12));
        assertThat(p.marketJumpProbabilityPerTick()).isCloseTo(0.5 / (6.5 * 3600), within(1e-12));
    }

    @Test
    void probabilitiesAreCappedAtOne() {
        var p = SimulationParameters.from(new MarketProperties.Simulator(Duration.ofHours(10), 1L, 1.0, 0.5, 0.4,
                Duration.ofSeconds(1), 0.02, 0.015, 260));
        assertThat(p.jumpProbabilityPerTick()).isEqualTo(1.0);
    }

    @Test
    void explicitSeedIsUsedAndMissingSeedIsRandom() {
        assertThat(SimulationParameters.from(simConfig(99)).seed()).isEqualTo(99L);
        var noSeed = new MarketProperties.Simulator(Duration.ofMillis(500), null, 1.0, 0.5, 0.4, Duration.ofHours(2),
                0.02, 0.015, 260);
        assertThat(SimulationParameters.from(noSeed).seed()).isNotEqualTo(SimulationParameters.from(noSeed).seed());
    }

    @Test
    void sameSeedGivesSameRng() {
        assertThat(SimulationParameters.newRng(5).nextLong()).isEqualTo(SimulationParameters.newRng(5).nextLong());
        assertThat(SimulationParameters.newRng(5).nextLong()).isNotEqualTo(SimulationParameters.newRng(6).nextLong());
    }
}
