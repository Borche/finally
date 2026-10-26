package com.finallyapp.market.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("market")
public record MarketProperties(
        @DefaultValue("AAPL,MSFT,GOOGL,AMZN,NVDA") List<String> defaultTickers,
        @DefaultValue Massive massive,
        @DefaultValue Simulator simulator) {

    public record Massive(
            String apiKey,
            @DefaultValue("https://api.massive.com") String baseUrl,
            @DefaultValue("15s") Duration pollInterval,
            @DefaultValue("1h") Duration eodRefreshInterval,
            @DefaultValue("200") int maxTickersPerRequest) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Never print the key. */
        @Override
        public String toString() {
            return "Massive[baseUrl=" + baseUrl + ", apiKey=" + (enabled() ? "***" : "<none>") + "]";
        }
    }

    public record Simulator(
            @DefaultValue("500ms") Duration tickInterval,
            Long seed,
            @DefaultValue("1.0") double timeScale,
            @DefaultValue("0.5") double marketBeta,
            @DefaultValue("0.4") double sectorBeta,
            @DefaultValue("2h") Duration meanTimeBetweenJumps,
            @DefaultValue("0.02") double jumpSigma,
            @DefaultValue("0.015") double marketJumpSigma,
            @DefaultValue("260") int historyDays) {}
}
