package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LastTrade(
        @JsonProperty("p") Double price,
        @JsonProperty("s") Double size,
        @JsonProperty("t") Long timestampNanos) {}
