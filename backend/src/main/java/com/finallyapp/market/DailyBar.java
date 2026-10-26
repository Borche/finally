package com.finallyapp.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One completed trading session. {@code date} is the America/New_York trading date. */
public record DailyBar(
        String ticker,
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {}
