package com.finallyapp.market.massive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.config.MarketProperties;
import com.finallyapp.market.testsupport.MutableClock;
import com.finallyapp.market.testsupport.RecordingSink;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MassiveEodModeTest {

    private static final String BASE = "https://api.massive.com";
    private static final String GROUPED = BASE + "/v2/aggs/grouped/locale/us/market/stocks/";

    private static final String FRIDAY = """
        {"status":"OK","resultsCount":2,"results":[
          {"T":"AAPL","o":230.1,"h":232.0,"l":229.5,"c":231.62,"v":51000000,"t":1792785600000},
          {"T":"MSFT","o":420.0,"h":425.0,"l":419.0,"c":424.5,"v":20000000,"t":1792785600000}]}
        """;
    private static final String THURSDAY = """
        {"status":"OK","resultsCount":1,"results":[
          {"T":"AAPL","o":228.0,"h":230.4,"l":227.9,"c":229.87,"v":48000000,"t":1792699200000}]}
        """;
    private static final String EMPTY = "{\"status\":\"OK\",\"resultsCount\":0,\"results\":[]}";

    // Monday 2026-10-26 11:00 ET → "yesterday" is Sunday → latest session Fri 10-23, previous Thu 10-22
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T15:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;

    private MassiveMarketDataSource create() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var props = new MarketProperties.Massive("test-key", BASE, Duration.ofSeconds(15), Duration.ofHours(1), 200);
        return new MassiveMarketDataSource(new MassiveClient(builder, BASE, "test-key"), props, clock,
                sleeps::add, false);
    }

    private void expectSnapshot403() {
        server.expect(requestTo(startsWith(BASE + "/v2/snapshot/")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"status\":\"NOT_AUTHORIZED\"}"));
    }

    private void expectGrouped(String date, String body) {
        server.expect(requestTo(GROUPED + date + "?adjusted=true"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void switchesToEodOn403AndWalksBackOverWeekend() {
        var source = create();
        expectSnapshot403();
        expectGrouped("2026-10-23", FRIDAY);
        expectGrouped("2026-10-22", THURSDAY);

        var sink = new RecordingSink();
        source.start(Set.of("AAPL"), sink);
        source.pollOnce();

        PriceQuote q = sink.latest.get("AAPL");
        assertThat(source.mode()).isEqualTo(MarketDataMode.MASSIVE_EOD);
        assertThat(q.mode()).isEqualTo(MarketDataMode.MASSIVE_EOD);
        assertThat(q.price()).isEqualByComparingTo("231.62");
        assertThat(q.previousClose()).isEqualByComparingTo("229.87");
        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-10-23T20:00:00Z"));   // 16:00 EDT
        assertThat(sleeps).containsExactly(Duration.ofSeconds(13));              // second grouped call spaced
        server.verify();

        source.pollOnce();                         // same ET date: served from cache, no further requests
        server.verify();
        source.close();
    }

    @Test
    void tickerMissingFromPreviousDayHasNullPreviousClose() {
        var source = create();
        expectSnapshot403();
        expectGrouped("2026-10-23", FRIDAY);
        expectGrouped("2026-10-22", THURSDAY);
        var sink = new RecordingSink();
        source.start(Set.of("MSFT", "UNKNOWN"), sink);
        source.pollOnce();
        assertThat(sink.latest).containsOnlyKeys("MSFT");
        assertThat(sink.latest.get("MSFT").previousClose()).isNull();
        source.close();
    }

    @Test
    void fetchEndOfDayReturnsLatestSessionBars() {
        var source = create();
        expectGrouped("2026-10-23", FRIDAY);
        expectGrouped("2026-10-22", THURSDAY);

        Map<String, DailyBar> bars = source.fetchEndOfDay(List.of("AAPL", "NOPE"));

        assertThat(bars).containsOnlyKeys("AAPL");
        DailyBar b = bars.get("AAPL");
        assertThat(b.date()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(b.open()).isEqualByComparingTo("230.10");
        assertThat(b.high()).isEqualByComparingTo("232.00");
        assertThat(b.low()).isEqualByComparingTo("229.50");
        assertThat(b.close()).isEqualByComparingTo("231.62");
        assertThat(b.volume()).isEqualTo(51_000_000L);
        source.close();
    }

    @Test
    void planErrorOnTodayWalksFurtherBack() {
        clock.set(Instant.parse("2026-10-27T15:00:00Z"));      // Tuesday: yesterday = Monday 10-26 (not released)
        var source = create();
        server.expect(requestTo(GROUPED + "2026-10-26?adjusted=true")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectGrouped("2026-10-23", FRIDAY);
        expectGrouped("2026-10-22", THURSDAY);

        assertThat(source.fetchEndOfDay(List.of("AAPL")).get("AAPL").date()).isEqualTo(LocalDate.of(2026, 10, 23));
        server.verify();
        source.close();
    }

    @Test
    void refreshesWhenEtDateChanges() {
        var source = create();
        expectGrouped("2026-10-23", FRIDAY);
        expectGrouped("2026-10-22", THURSDAY);
        source.fetchEndOfDay(List.of("AAPL"));
        server.verify();

        clock.set(Instant.parse("2026-10-27T15:00:00Z"));      // Tuesday → anchor Monday
        expectGrouped("2026-10-26", FRIDAY);
        expectGrouped("2026-10-23", THURSDAY);
        assertThat(source.fetchEndOfDay(List.of("AAPL")).get("AAPL").date()).isEqualTo(LocalDate.of(2026, 10, 26));
        source.close();
    }

    @Test
    void throwsWhenNoTradingDayHasData() {
        var source = create();
        server.expect(ExpectedCount.times(5), requestTo(startsWith(GROUPED)))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.fetchEndOfDay(List.of("AAPL")))
                .isInstanceOf(MassiveApiException.class)
                .extracting(e -> ((MassiveApiException) e).status()).isEqualTo(404);
        server.verify();
        source.close();
    }

    @Test
    void unauthorizedStopsPolling() {
        var source = create();
        server.expect(requestTo(startsWith(BASE + "/v2/snapshot/"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        source.start(Set.of("AAPL"), new RecordingSink());
        source.runCycle();
        assertThat(source.isStopped()).isTrue();
        source.close();
    }

    @Test
    void eodFailureOtherThanPlanIsRetriedNotFatal() {
        var source = create();
        expectSnapshot403();
        server.expect(requestTo(startsWith(GROUPED))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        source.start(Set.of("AAPL"), new RecordingSink());
        source.runCycle();
        assertThat(source.isStopped()).isFalse();
        assertThat(source.mode()).isEqualTo(MarketDataMode.MASSIVE_EOD);
        source.close();
    }
}
