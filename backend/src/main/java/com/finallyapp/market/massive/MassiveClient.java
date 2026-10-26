package com.finallyapp.market.massive;

import com.finallyapp.market.massive.dto.AggregateBar;
import com.finallyapp.market.massive.dto.AggregatesResponse;
import com.finallyapp.market.massive.dto.SnapshotResponse;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** Thin typed wrapper over the Massive endpoints we use. Stateless and thread-safe. */
public class MassiveClient {

    private final RestClient http;

    public MassiveClient(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)   // never ?apiKey= (ends up in logs)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw toException(res.getStatusCode().value(), req.getURI().getPath(), res);
                })
                .build();
    }

    /** Current snapshot for up to ~200 tickers in one call (Starter+). Unknown tickers are simply absent. */
    public List<TickerSnapshot> snapshots(Collection<String> tickers) {
        SnapshotResponse body = http.get()
                .uri(u -> u.path("/v2/snapshot/locale/us/markets/stocks/tickers")
                        .queryParam("tickers", String.join(",", tickers))
                        .build())
                .retrieve()
                .body(SnapshotResponse.class);
        return body == null || body.tickers() == null ? List.of() : body.tickers();
    }

    /** EOD bars for every US stock on {@code date} (all plans). Empty on weekends and holidays. */
    public List<AggregateBar> groupedDaily(LocalDate date) {
        AggregatesResponse body = http.get()
                .uri("/v2/aggs/grouped/locale/us/market/stocks/{date}?adjusted=true", date)
                .retrieve()
                .body(AggregatesResponse.class);
        return body == null || body.results() == null ? List.of() : body.results();
    }

    private static MassiveApiException toException(int status, String path, ClientHttpResponse res) {
        String body;
        try {
            body = new String(res.getBody().readNBytes(500), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "<unreadable>";
        }
        return switch (status) {
            case 401 -> new MassiveAuthException(path, body);
            case 403 -> new MassivePlanException(path, body);
            case 429 -> new MassiveRateLimitException(path, body);
            default -> new MassiveApiException(status, path, body);
        };
    }
}
