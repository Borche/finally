package com.finallyapp.market.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Prices {

    public static final int SCALE = 4;

    private Prices() {}

    /** Exact-as-possible conversion of an API double. Used for Massive prices. */
    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Rounds to a realistic tick: cents at or above $1, $0.0001 below. Result always has scale 4. */
    public static BigDecimal toTick(double value) {
        int places = value >= 1.0 ? 2 : 4;
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).setScale(SCALE);
    }
}
