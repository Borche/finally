package com.finallyapp.market;

import java.util.Locale;
import java.util.regex.Pattern;

/** All tickers are normalised once, at the facade boundary. Sources only ever see normalised tickers. */
public final class Tickers {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private Tickers() {}

    /** Trims and upper-cases; throws IllegalArgumentException for anything that cannot be a US ticker. */
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Ticker must not be null");
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(t).matches()) throw new IllegalArgumentException("Invalid ticker: '" + raw + "'");
        return t;
    }
}
