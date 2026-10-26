package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregateBar(
        @JsonProperty("T") String ticker,
        @JsonProperty("o") double open,
        @JsonProperty("h") double high,
        @JsonProperty("l") double low,
        @JsonProperty("c") double close,
        @JsonProperty("v") double volume,
        @JsonProperty("t") long timestampMillis) {}
