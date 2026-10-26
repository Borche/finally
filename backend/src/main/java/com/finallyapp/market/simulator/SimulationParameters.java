package com.finallyapp.market.simulator;

import com.finallyapp.market.config.MarketProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/** Per-tick constants derived once from configuration. */
record SimulationParameters(
        Duration tickInterval,
        double dt,                             // years of trading time per tick
        double marketBeta,
        double sectorBeta,
        double jumpProbabilityPerTick,
        double jumpSigma,
        double marketJumpProbabilityPerTick,
        double marketJumpSigma,
        int historyDays,
        long seed) {

    private static final double TRADING_SECONDS_PER_DAY = 6.5 * 3600;
    private static final double TRADING_SECONDS_PER_YEAR = 252 * TRADING_SECONDS_PER_DAY;

    static SimulationParameters from(MarketProperties.Simulator c) {
        // Δt = tickInterval × timeScale / (252 × 6.5 h)   ≈ 8.48e-8 for 500 ms at timeScale 1
        double simSecondsPerTick = c.tickInterval().toMillis() / 1000.0 * c.timeScale();
        long seed = c.seed() != null ? c.seed() : ThreadLocalRandom.current().nextLong();
        return new SimulationParameters(
                c.tickInterval(),
                simSecondsPerTick / TRADING_SECONDS_PER_YEAR,
                c.marketBeta(),
                c.sectorBeta(),
                Math.min(1.0, simSecondsPerTick / c.meanTimeBetweenJumps().toSeconds()),
                c.jumpSigma(),
                Math.min(1.0, simSecondsPerTick / TRADING_SECONDS_PER_DAY),   // ≈ one market event per sim day
                c.marketJumpSigma(),
                c.historyDays(),
                seed);
    }

    static RandomGenerator newRng(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }
}
