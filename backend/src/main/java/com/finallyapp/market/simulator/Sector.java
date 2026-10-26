package com.finallyapp.market.simulator;

import java.util.List;

enum Sector {
    TECH, FINANCE, HEALTH, ENERGY, CONSUMER, INDUSTRIAL, INDEX;

    /** Sectors that synthesized (unknown) tickers can be assigned to. */
    static final List<Sector> STOCK_SECTORS = List.of(TECH, FINANCE, HEALTH, ENERGY, CONSUMER, INDUSTRIAL);
}
