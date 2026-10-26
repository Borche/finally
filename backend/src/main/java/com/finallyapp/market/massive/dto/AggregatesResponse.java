package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregatesResponse(String status, Integer resultsCount, List<AggregateBar> results) {}
