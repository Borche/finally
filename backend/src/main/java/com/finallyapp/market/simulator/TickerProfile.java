package com.finallyapp.market.simulator;

/** Immutable per-ticker parameters. */
record TickerProfile(
        String ticker,
        double initialPrice,
        double annualDrift,        // μ
        double annualVolatility,   // σ
        Sector sector,
        double marketBeta,         // β_m
        double sectorBeta,         // β_s
        long averageVolume) {      // shares/day, for synthetic bars

    TickerProfile {
        if (marketBeta * marketBeta + sectorBeta * sectorBeta > 1.0) {
            throw new IllegalArgumentException("β_m² + β_s² must be ≤ 1 for " + ticker);
        }
    }
}
