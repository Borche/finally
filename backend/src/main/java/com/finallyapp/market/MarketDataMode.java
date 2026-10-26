package com.finallyapp.market;

public enum MarketDataMode {
    /** Simulator: no API key configured. */
    SIMULATED("Simulated"),
    /** Massive snapshot polling (Starter+: 15-min delayed, Advanced+: real-time). */
    MASSIVE_LIVE("Live"),
    /** Massive end-of-day only (free Basic plan): price == last close. */
    MASSIVE_EOD("End of day");

    private final String label;

    MarketDataMode(String label) { this.label = label; }

    /** Short human-readable label for the UI. */
    public String label() { return label; }
}
