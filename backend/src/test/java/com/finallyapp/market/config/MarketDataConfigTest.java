package com.finallyapp.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.MarketDataService;
import com.finallyapp.market.massive.MassiveMarketDataSource;
import com.finallyapp.market.simulator.SimulatedMarketDataSource;
import com.finallyapp.market.spi.MarketDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class MarketDataConfigTest {

    // base-url localhost:1 makes the one asynchronous first poll fail immediately, never reaching the internet.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketDataConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues("market.massive.base-url=http://localhost:1", "market.massive.poll-interval=1h",
                    "market.simulator.seed=42");

    @Test
    void blankKeyUsesSimulator() {
        runner.withPropertyValues("market.massive.api-key=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(MarketDataSource.class)).isInstanceOf(SimulatedMarketDataSource.class);
                    assertThat(ctx.getBean(MarketDataService.class).mode()).isEqualTo(MarketDataMode.SIMULATED);
                });
    }

    @Test
    void whitespaceKeyUsesSimulator() {
        runner.withPropertyValues("market.massive.api-key=   ")
                .run(ctx -> assertThat(ctx.getBean(MarketDataSource.class))
                        .isInstanceOf(SimulatedMarketDataSource.class));
    }

    @Test
    void missingKeyUsesSimulator() {
        runner.run(ctx -> assertThat(ctx.getBean(MarketDataSource.class))
                .isInstanceOf(SimulatedMarketDataSource.class));
    }

    @Test
    void keyUsesMassive() {
        runner.withPropertyValues("market.massive.api-key=abc")
                .run(ctx -> assertThat(ctx.getBean(MarketDataSource.class))
                        .isInstanceOf(MassiveMarketDataSource.class));
    }

    @Test
    void serviceIsStartedWithDefaultTickersAndSimulatorPublishesImmediately() {
        runner.withPropertyValues("market.default-tickers=aapl,msft")
                .run(ctx -> {
                    MarketDataService service = ctx.getBean(MarketDataService.class);
                    assertThat(service.trackedTickers()).containsExactlyInAnyOrder("AAPL", "MSFT");
                    assertThat(service.getAllPrices()).containsOnlyKeys("AAPL", "MSFT");
                });
    }

    @Test
    void propertiesBindWithDefaults() {
        runner.run(ctx -> {
            MarketProperties p = ctx.getBean(MarketProperties.class);
            assertThat(p.defaultTickers()).containsExactly("AAPL", "MSFT", "GOOGL", "AMZN", "NVDA");
            assertThat(p.massive().enabled()).isFalse();
            assertThat(p.massive().baseUrl()).isEqualTo("http://localhost:1");
            assertThat(p.massive().eodRefreshInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(p.massive().maxTickersPerRequest()).isEqualTo(200);
            assertThat(p.simulator().tickInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(p.simulator().seed()).isEqualTo(42L);
            assertThat(p.simulator().timeScale()).isEqualTo(1.0);
            assertThat(p.simulator().historyDays()).isEqualTo(260);
            assertThat(p.simulator().meanTimeBetweenJumps()).isEqualTo(Duration.ofHours(2));
        });
    }

    @Test
    void massivePropertiesNeverPrintTheKey() {
        var p = new MarketProperties.Massive("super-secret", "https://x", Duration.ofSeconds(1), Duration.ofHours(1), 1);
        assertThat(p.toString()).doesNotContain("super-secret").contains("***");
        assertThat(new MarketProperties.Massive("", "https://x", Duration.ofSeconds(1), Duration.ofHours(1), 1).toString())
                .contains("<none>");
    }
}
