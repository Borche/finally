package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerSnapshot(
        String ticker,
        Double todaysChange,
        Double todaysChangePerc,
        Long updated,              // Unix nanoseconds
        Bar day,
        Bar min,
        Bar prevDay,
        LastTrade lastTrade) {}    // null unless the plan includes trades
