package com.finallyapp.market.massive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.config.MarketProperties;
import com.finallyapp.market.spi.MarketDataSource;
import com.finallyapp.market.testsupport.MarketDataSourceContractTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** LIVE mode (snapshot polling) behaviour, including the shared source contract. */
class MassiveMarketDataSourceTest extends MarketDataSourceContractTest {

    private static final String BASE = "https://api.massive.com";
    private static final String SNAPSHOT_URL = BASE + "/v2/snapshot/";

    // Monday 2026-10-26 11:00 ET
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC);
    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private MassiveMarketDataSource massive;

    static final String SNAPSHOT = """
        { "status": "OK", "tickers": [
          { "ticker": "AAPL", "updated": 1792940400000000000,
            "min": { "c": 231.40, "t": 1792940340000 }, "prevDay": { "c": 229.87 } },
          { "ticker": "MSFT", "updated": 1792940400000000000,
            "lastTrade": { "p": 421.15, "t": 1792940399000000000 },
            "min": { "c": 421.00 }, "prevDay": { "c": 418.00 } },
          { "ticker": "NVDA", "updated": 1792940400000000000,
            "day": { "c": 131.25 }, "prevDay": { "c": 129.10 } } ] }
        """;

    private MassiveMarketDataSource create(int maxPerRequest) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var props = new MarketProperties.Massive("test-key", BASE, Duration.ofSeconds(15), Duration.ofHours(1),
                maxPerRequest);
        return new MassiveMarketDataSource(new MassiveClient(builder, BASE, "test-key"), props, clock,
                sleeps::add, false);
    }

    @Override
    protected MarketDataSource newSource() {
        massive = create(200);
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(SNAPSHOT_URL)))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        return massive;
    }

    @Override
    protected void advance() {
        massive.pollOnce();
    }

    @Test
    void startsInLiveMode() {
        assertThat(massive.mode()).isEqualTo(MarketDataMode.MASSIVE_LIVE);
    }

    @Test
    void priceFallbackChain() {
        massive.start(Set.of("AAPL", "MSFT", "NVDA"), sink);
        massive.pollOnce();
        assertThat(sink.latest.get("MSFT").price()).isEqualByComparingTo("421.15");   // lastTrade.p
        assertThat(sink.latest.get("AAPL").price()).isEqualByComparingTo("231.40");   // min.c
        assertThat(sink.latest.get("NVDA").price()).isEqualByComparingTo("131.25");   // day.c
        assertThat(sink.latest.get("AAPL").previousClose()).isEqualByComparingTo("229.87");
        assertThat(sink.latest.get("AAPL").mode()).isEqualTo(MarketDataMode.MASSIVE_LIVE);
    }

    @Test
    void asOfComesFromSnapshotUpdatedNanos() {
        massive.start(Set.of("AAPL"), sink);
        massive.pollOnce();
        assertThat(sink.latest.get("AAPL").asOf()).isEqualTo(Instant.ofEpochSecond(1792940400L));
    }

    @Test
    void tickersNotTrackedAreIgnored() {
        massive.start(Set.of("AAPL"), sink);
        massive.pollOnce();
        assertThat(sink.latest).containsOnlyKeys("AAPL");
    }

    @Test
    void successfulCycleDoesNotStop() {
        massive.start(Set.of("AAPL"), sink);
        massive.runCycle();
        assertThat(massive.isStopped()).isFalse();
        assertThat(sink.latest).containsKey("AAPL");
    }

    @Test
    void closeStopsCycles() {
        massive.start(Set.of("AAPL"), sink);
        massive.close();
        massive.runCycle();                                  // no request expected, none made
        assertThat(massive.isStopped()).isTrue();
        assertThat(sink.all).isEmpty();
    }

    @Test
    void tickersAreChunkedByMaxPerRequest() {
        var chunked = create(2);
        server.expect(ExpectedCount.times(3), requestTo(startsWith(SNAPSHOT_URL)))
                .andRespond(withSuccess("{\"status\":\"OK\",\"tickers\":[]}", MediaType.APPLICATION_JSON));
        chunked.start(Set.of("A", "B", "C", "D", "E"), sink);
        chunked.pollOnce();
        server.verify();
        chunked.close();
    }

    @Test
    void rateLimitKeepsCachedPricesAndDoesNotStop() {
        var source = create(200);
        server.expect(ExpectedCount.once(), requestTo(startsWith(SNAPSHOT_URL)))
                .andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(SNAPSHOT_URL)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        source.start(Set.of("AAPL"), sink);
        source.runCycle();
        PriceQuote before = sink.latest.get("AAPL");
        source.runCycle();                                   // 429: swallowed, retried later
        assertThat(source.isStopped()).isFalse();
        assertThat(sink.latest.get("AAPL")).isEqualTo(before);
        assertThat(source.mode()).isEqualTo(MarketDataMode.MASSIVE_LIVE);
        source.close();
    }

    @Test
    void serverErrorDoesNotStop() {
        var source = create(200);
        server.expect(requestTo(startsWith(SNAPSHOT_URL))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        source.start(Set.of("AAPL"), sink);
        source.runCycle();
        assertThat(source.isStopped()).isFalse();
        source.close();
    }

    @Test
    void backoffDoublesAndCaps() {
        Duration base = Duration.ofSeconds(15);
        assertThat(MassiveMarketDataSource.backoff(base, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(MassiveMarketDataSource.backoff(base, 3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(MassiveMarketDataSource.backoff(base, 9)).isEqualTo(Duration.ofMinutes(5));
        assertThat(MassiveMarketDataSource.backoff(base, 1_000)).isEqualTo(Duration.ofMinutes(5));
    }
}
