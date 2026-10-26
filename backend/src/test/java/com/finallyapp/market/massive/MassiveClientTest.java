package com.finallyapp.market.massive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finallyapp.market.massive.dto.AggregateBar;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MassiveClientTest {

    private static final String BASE = "https://api.massive.com";

    private MockRestServiceServer server;
    private MassiveClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MassiveClient(builder, BASE, "secret-key");
    }

    @Test
    void snapshotsSendsBearerHeaderAndTickersAndParsesBody() {
        server.expect(method(HttpMethod.GET))
                .andExpect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2/snapshot/locale/us/markets/stocks/tickers")))
                .andExpect(queryParam("tickers", "AAPL,MSFT"))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andRespond(withSuccess("""
                    {"status":"OK","count":1,"unknownField":true,"tickers":[
                      {"ticker":"AAPL","todaysChange":0.98,"updated":1605195918306274000,
                       "day":{"o":119.62,"h":120.53,"l":118.81,"c":120.4229,"v":28727868,"vw":119.725},
                       "min":{"o":1,"h":2,"l":0.5,"c":120.4201,"v":270796,"av":28724441,"vw":120.4,"n":762,"t":1684428720000},
                       "prevDay":{"c":119.49},
                       "lastTrade":{"p":120.47,"s":236,"x":10,"i":"4046","c":[14,41],"t":1605195918306274000},
                       "lastQuote":{"p":120.46,"s":8,"P":120.47,"S":4,"t":1605195918507251700}}]}
                    """, MediaType.APPLICATION_JSON));

        List<TickerSnapshot> out = client.snapshots(List.of("AAPL", "MSFT"));

        assertThat(out).hasSize(1);
        TickerSnapshot s = out.getFirst();
        assertThat(s.ticker()).isEqualTo("AAPL");
        assertThat(s.updated()).isEqualTo(1605195918306274000L);
        assertThat(s.lastTrade().price()).isEqualTo(120.47);
        assertThat(s.min().close()).isEqualTo(120.4201);
        assertThat(s.min().timestampMillis()).isEqualTo(1684428720000L);
        assertThat(s.prevDay().close()).isEqualTo(119.49);
        assertThat(s.day().open()).isEqualTo(119.62);
        server.verify();
    }

    @Test
    void snapshotsToleratesMissingTickersArray() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2/snapshot/")))
                .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));
        assertThat(client.snapshots(List.of("AAPL"))).isEmpty();
    }

    @Test
    void groupedDailyParsesBars() {
        server.expect(requestTo(BASE + "/v2/aggs/grouped/locale/us/market/stocks/2026-10-23?adjusted=true"))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andRespond(withSuccess("""
                    {"status":"OK","adjusted":true,"queryCount":1,"resultsCount":1,"request_id":"x","results":[
                      {"T":"AAPL","o":230.1,"h":232.0,"l":229.5,"c":231.62,"v":51000000,"vw":231.0,"n":10,"t":1792785600000}]}
                    """, MediaType.APPLICATION_JSON));

        List<AggregateBar> bars = client.groupedDaily(LocalDate.of(2026, 10, 23));

        assertThat(bars).containsExactly(new AggregateBar("AAPL", 230.1, 232.0, 229.5, 231.62, 51_000_000, 1792785600000L));
    }

    @Test
    void groupedDailyEmptyOnNonTradingDay() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2/aggs/grouped/")))
                .andRespond(withSuccess("{\"status\":\"OK\",\"resultsCount\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.groupedDaily(LocalDate.of(2026, 10, 24))).isEmpty();
    }

    @Test
    void mapsHttpStatusesToExceptions() {
        assertThatThrownBy(() -> fail(HttpStatus.UNAUTHORIZED)).isInstanceOf(MassiveAuthException.class);
        assertThatThrownBy(() -> fail(HttpStatus.FORBIDDEN)).isInstanceOf(MassivePlanException.class);
        assertThatThrownBy(() -> fail(HttpStatus.TOO_MANY_REQUESTS)).isInstanceOf(MassiveRateLimitException.class);
        assertThatThrownBy(() -> fail(HttpStatus.INTERNAL_SERVER_ERROR))
                .isInstanceOf(MassiveApiException.class)
                .isNotInstanceOf(MassiveAuthException.class)
                .extracting(e -> ((MassiveApiException) e).status()).isEqualTo(500);
    }

    @Test
    void exceptionMessageIncludesStatusPathAndBodyButNotKey() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"status\":\"NOT_AUTHORIZED\"}"));
        assertThatThrownBy(() -> client.snapshots(List.of("AAPL")))
                .hasMessageContaining("403")
                .hasMessageContaining("/v2/snapshot/locale/us/markets/stocks/tickers")
                .hasMessageContaining("NOT_AUTHORIZED")
                .hasMessageNotContaining("secret-key");
    }

    private void fail(HttpStatus status) {
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE))).andRespond(withStatus(status));
        client.snapshots(List.of("AAPL"));
    }
}
