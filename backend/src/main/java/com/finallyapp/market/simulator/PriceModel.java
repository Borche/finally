package com.finallyapp.market.simulator;

import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GBM with market/sector factors and Poisson jumps. Stateless: all randomness comes from the caller's RNG.
 *
 *   Z_i       = β_m·Z_market + β_s·Z_sector + √(1 − β_m² − β_s²)·Z_idio        (still N(0,1))
 *   log-ret   = (μ − σ²/2)·Δt + σ·√Δt·Z_i + β_m·J_market + [J_idio with prob p_jump]
 *   S(t+Δt)   = max(S(t)·exp(log-ret), 0.01)
 */
final class PriceModel {

    private static final Logger log = LoggerFactory.getLogger(PriceModel.class);

    private final SimulationParameters p;
    private final double sqrtDt;

    PriceModel(SimulationParameters p) {
        this.p = p;
        this.sqrtDt = Math.sqrt(p.dt());
    }

    double step(double price, TickerProfile prof, double zMarket, double zSector,
                double marketJump, RandomGenerator rng) {
        double bm = prof.marketBeta();
        double bs = prof.sectorBeta();
        double z = bm * zMarket + bs * zSector + Math.sqrt(1 - bm * bm - bs * bs) * rng.nextGaussian();

        double sigma = prof.annualVolatility();
        double logReturn = (prof.annualDrift() - 0.5 * sigma * sigma) * p.dt() + sigma * sqrtDt * z
                + bm * marketJump;                                   // 0 on almost every tick

        if (rng.nextDouble() < p.jumpProbabilityPerTick()) {
            double jump = rng.nextGaussian() * p.jumpSigma();
            logReturn += jump;
            if (log.isDebugEnabled()) {
                log.debug("SIM EVENT: {} jumped {}%", prof.ticker(), String.format("%+.1f", Math.expm1(jump) * 100));
            }
        }
        return Math.max(price * Math.exp(logReturn), 0.01);
    }
}
