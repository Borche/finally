package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bar(
        @JsonProperty("o") Double open,
        @JsonProperty("h") Double high,
        @JsonProperty("l") Double low,
        @JsonProperty("c") Double close,
        @JsonProperty("v") Double volume,
        @JsonProperty("vw") Double vwap,
        @JsonProperty("t") Long timestampMillis) {}
