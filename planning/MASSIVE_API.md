# Massive API (formerly Polygon.io) — Stock Price Reference

Research notes for retrieving **real-time** and **end-of-day** prices for **multiple tickers** from
[Massive](https://massive.com). Polygon.io rebranded as Massive on 2025-10-30; existing API keys keep
working and `api.polygon.io` remains supported for a transition period, but new code should use the
`massive.com` hosts below.

Researched 2026-10-26 against the official docs (index: <https://massive.com/docs/llms.txt>).

---

## 1. Basics

| Item | Value |
|---|---|
| REST base URL | `https://api.massive.com` |
| WebSocket (real-time) | `wss://socket.massive.com/stocks` |
| WebSocket (15-min delayed) | `wss://delayed.massive.com/stocks` |
| Auth (REST) | `Authorization: Bearer <KEY>` header **or** `?apiKey=<KEY>` query parameter |
| Auth (WebSocket) | First message after connect: `{"action":"auth","params":"<KEY>"}` |
| Ticker symbols | **Case-sensitive** — always send upper case (`AAPL`, not `aapl`) |
| Timestamps | Unix epoch, UTC. REST bars use **milliseconds**; snapshot `updated` and `lastTrade.t`/`lastQuote.t` use **nanoseconds** |

Prefer the `Authorization` header in our code so the key never ends up in logged URLs.

### 1.1 Plans — what each tier can do

This decides which endpoints our backend may use (source: <https://massive.com/pricing>, per-endpoint plan tables).

| Plan | Price/mo | Rate limit | Recency | Snapshots | WebSocket | Last trade |
|---|---|---|---|---|---|---|
| **Basic** | $0 | **5 calls/min** | End of day | ❌ | ❌ | ❌ |
| Starter | $29 | Unlimited | 15-min delayed | ✅ | ✅ (aggregates) | ❌ |
| Developer | $79 | Unlimited | 15-min delayed | ✅ | ✅ (+ trades) | ✅ |
| Advanced | $199 | Unlimited | **Real-time** | ✅ | ✅ | ✅ |
| Business | custom | Unlimited | Real-time | ✅ | ✅ | depends on add-ons |

Consequences for us:

* On **Basic** (the free plan most developers will use) we can only fetch **previous-day / end-of-day bars**. Current-day
  bars return **HTTP 403** until just after midnight ET. Exceeding 5 req/min returns **HTTP 429**.
* "Real-time" in the product sense (a price that moves during the day) needs **Starter+** (15-min delayed) or
  **Advanced** (true real-time).
* Fields such as `lastTrade` / `lastQuote` in snapshots are **only present if the plan includes trades/quotes** —
  parsers must treat them as optional.

---

## 2. Endpoints for multiple tickers

| Need | Endpoint | Tickers per call | Plan |
|---|---|---|---|
| Current price, many tickers | `GET /v2/snapshot/locale/us/markets/stocks/tickers?tickers=A,B,C` | many (one call) | Starter+ |
| Current price, many tickers (newer, paginated) | `GET /v3/snapshot?ticker.any_of=A,B,C` | up to 250 | Starter+ |
| Current price, one ticker | `GET /v2/snapshot/locale/us/markets/stocks/tickers/{ticker}` | 1 | Starter+ |
| End of day, **all** tickers for a date | `GET /v2/aggs/grouped/locale/us/market/stocks/{date}` | all (one call) | All |
| Previous day bar, one ticker | `GET /v2/aggs/ticker/{ticker}/prev` | 1 | All |
| Historical bars, one ticker | `GET /v2/aggs/ticker/{ticker}/range/{mult}/{timespan}/{from}/{to}` | 1 | All |
| Last trade, one ticker | `GET /v2/last/trade/{ticker}` | 1 | Developer+ |
| Streaming | WebSocket `AM.*` / `A.*` / `T.*` | any | Starter+ |

**Key insight for the free plan:** the *grouped daily* endpoint returns the EOD bar for every US stock in **one
request**, so end-of-day prices for an arbitrarily large watchlist cost a single call. That keeps us far
inside the 5 calls/min budget.

### 2.1 Full Market Snapshot (multi-ticker current prices)

`GET /v2/snapshot/locale/us/markets/stocks/tickers`

| Query param | Type | Default | Notes |
|---|---|---|---|
| `tickers` | comma-separated string | *(all tickers)* | e.g. `AAPL,TSLA,GOOG`. Omit for the whole market (large!) |
| `include_otc` | boolean | `false` | include OTC securities |

```bash
curl -s -H "Authorization: Bearer $MASSIVE_API_KEY" \
  "https://api.massive.com/v2/snapshot/locale/us/markets/stocks/tickers?tickers=AAPL,MSFT,NVDA"
```

Response (shape; values from the docs' single-ticker example):

```json
{
  "status": "OK",
  "count": 1,
  "tickers": [
    {
      "ticker": "AAPL",
      "todaysChange": 0.98,
      "todaysChangePerc": 0.82,
      "updated": 1605195918306274000,
      "day":     { "o": 119.62, "h": 120.53, "l": 118.81, "c": 120.4229, "v": 28727868, "vw": 119.725 },
      "min":     { "o": 120.435, "h": 120.468, "l": 120.37, "c": 120.4201, "v": 270796, "av": 28724441,
                   "vw": 120.4129, "n": 762, "t": 1684428720000 },
      "prevDay": { "o": 117.19, "h": 119.63, "l": 116.44, "c": 119.49, "v": 110597265, "vw": 118.4998 },
      "lastTrade": { "p": 120.47, "s": 236, "x": 10, "i": "4046", "c": [14, 41], "t": 1605195918306274000 },
      "lastQuote": { "p": 120.46, "s": 8, "P": 120.47, "S": 4, "t": 1605195918507251700 }
    }
  ]
}
```

Field glossary:

| Object | Key | Meaning |
|---|---|---|
| bar (`day`, `min`, `prevDay`) | `o` `h` `l` `c` | open / high / low / close |
| | `v` | volume |
| | `vw` | volume-weighted average price |
| `min` only | `av` | accumulated volume for the day |
| | `n` | number of trades in the minute |
| | `t` | minute start, Unix **ms** |
| `lastTrade` | `p` / `s` | trade price / size |
| | `x`, `i`, `c` | exchange id, trade id, condition codes |
| | `t` | SIP timestamp, Unix **ns** |
| `lastQuote` | `p` / `s` | **bid** price / size (lower case) |
| | `P` / `S` | **ask** price / size (upper case) |
| top level | `todaysChange`, `todaysChangePerc` | vs. previous close |
| | `updated` | last update, Unix **ns** |

**Gotchas**

* Snapshot data is **cleared at ~3:30 AM ET** and repopulates as pre-market trades arrive. Early in the morning
  `day` may be all zeros. To pick a "current price", use this fallback chain:
  `lastTrade.p` → `min.c` → `day.c` → `prevDay.c` (skipping missing or zero values).
* `lastTrade` is missing on Starter (no trades entitlement), so on Starter the price comes from `min.c`.
* Unknown tickers are simply absent from `tickers[]`. They do not cause an error.

### 2.2 Unified Snapshot (v3, alternative)

`GET /v3/snapshot?ticker.any_of=AAPL,MSFT&limit=250`

* Up to **250 tickers** per request via `ticker.any_of`, `limit` max 250 (default **10** — always set it).
* Results include `session` (change, change_percent, open, high, low, close, previous_close, volume, early/late
  session changes), `last_trade`, `last_quote`, `last_minute`, `market_status` (`open`, `closed`,
  `early_trading`, `late_trading`).
* Unknown ticker → per-item `"error": "NOT_FOUND"`. Paginate via `next_url`.
* Uses snake_case, self-describing fields, and handles multiple asset classes. The v2 endpoint is simpler for a
  stocks-only app, so **we use v2** and keep v3 as a fallback if v2 is ever deprecated.

### 2.3 Grouped Daily (end of day, all tickers, one call)

`GET /v2/aggs/grouped/locale/us/market/stocks/{date}` where `date` = `YYYY-MM-DD`

| Query param | Default | Notes |
|---|---|---|
| `adjusted` | `true` | split-adjusted |
| `include_otc` | `false` | |

```bash
curl -s -H "Authorization: Bearer $MASSIVE_API_KEY" \
  "https://api.massive.com/v2/aggs/grouped/locale/us/market/stocks/2026-10-23?adjusted=true"
```

```json
{
  "status": "OK",
  "adjusted": true,
  "queryCount": 10951,
  "resultsCount": 10951,
  "request_id": "…",
  "results": [
    { "T": "AAPL", "o": 0.0, "h": 0.0, "l": 0.0, "c": 0.0, "v": 0, "vw": 0.0, "n": 0, "t": 0 }
  ]
}
```

* `T` = ticker, `t` = Unix ms timestamp of the bar.
* Returns roughly 10k rows (~1 MB), so filter client-side to our tickers.
* For a weekend or holiday, `resultsCount` is 0. Walk back day by day to the last trading day.
* On Basic, the current trading day is **403** until just after midnight ET. Request "yesterday" (ET), not "today".

### 2.4 Previous Day Bar (one ticker)

`GET /v2/aggs/ticker/{ticker}/prev?adjusted=true`

```json
{
  "ticker": "AAPL", "status": "OK", "adjusted": true, "queryCount": 1, "resultsCount": 1,
  "results": [ { "T": "AAPL", "o": 115.55, "h": 117.59, "l": 114.13, "c": 115.97,
                 "v": 131704427, "vw": 116.3058, "n": 0, "t": 0 } ]
}
```

This handles trading-day logic for you (always the *last completed* session). However, it costs **one call per ticker**,
so on Basic only use it for one or two tickers, such as a ticker just added to a watchlist.

### 2.5 Custom Bars (history)

`GET /v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{from}/{to}`
e.g. `/v2/aggs/ticker/AAPL/range/1/day/2026-01-01/2026-10-23?adjusted=true&sort=asc&limit=5000`

* `timespan`: `minute`, `hour`, `day`, `week`, `month`, … ; `from`/`to`: `YYYY-MM-DD` or epoch ms.
* `limit` default 5000, max 50000. If `next_url` is present, follow it (it carries a `cursor`).
* Results: `o h l c v vw n t` (t = window start, ms). History depth: 2y Basic, 5y Starter, 10y Developer, all Advanced.

### 2.6 Last Trade (one ticker)

`GET /v2/last/trade/{ticker}` → `results.p` (price), `results.s` (size), `results.t` (SIP ns). Developer+ only.
Not needed in our design, because the snapshot already includes `lastTrade`.

---

## 3. WebSocket streaming (Starter+)

Protocol:

1. Connect to `wss://socket.massive.com/stocks` (real-time, Advanced) or `wss://delayed.massive.com/stocks`
   (Starter/Developer).
2. Server sends `[{"ev":"status","status":"connected",…}]`.
3. Send `{"action":"auth","params":"<KEY>"}`. Wait for a status message with `"status":"auth_success"`.
4. Send `{"action":"subscribe","params":"AM.AAPL,AM.MSFT"}`. Use `AM.*` for all tickers.
   Unsubscribe with `{"action":"unsubscribe","params":"AM.AAPL"}`.
5. Messages arrive as **JSON arrays** of events (several events can be batched in one frame).

| Channel | Prefix | Plan | Content |
|---|---|---|---|
| Aggregates per minute | `AM.` | Starter+ | 1-minute OHLCV bar |
| Aggregates per second | `A.` | Starter+ | 1-second OHLCV bar |
| Trades | `T.` | Developer+ | every trade |
| Quotes | `Q.` | Advanced+ | NBBO quotes |

Minute aggregate event:

```json
{ "ev": "AM", "sym": "MSFT", "v": 4110, "av": 9470157, "op": 412.10, "vw": 413.02,
  "o": 413.00, "c": 413.05, "h": 413.10, "l": 412.95, "a": 412.80, "z": 685,
  "s": 1610144640000, "e": 1610144700000 }
```

`op` = today's official open, `a` = today's VWAP, `av` = accumulated volume, `s`/`e` = window start/end (ms).

Trade event: `{ "ev":"T", "sym":"MSFT", "p":114.125, "s":100, "x":4, "i":"12345", "z":3, "c":[0,12], "t":1536036818784, "q":3681328 }`
(`t` in **ms** here, unlike REST's ns).

**Decision for v1:** we **poll the REST snapshot** instead of using WebSockets. Polling works on every paid tier, needs
no reconnect/resubscribe logic, and at a 15 s interval (or 60 s for delayed data) its latency is good enough
for a trading-simulation UI. WebSocket support is a documented extension point in `MARKET_INTERFACE.md`.

---

## 4. Errors and limits

| HTTP | Cause | Handling |
|---|---|---|
| 401 | missing/invalid key | fail fast at startup, log clearly |
| 403 | endpoint/recency not in plan (e.g. snapshot on Basic, today's bar on Basic) | switch to EOD mode (see `MARKET_INTERFACE.md`) |
| 429 | rate limit (Basic: 5/min) | back off; never retry in a tight loop |
| 5xx | server error | retry with exponential backoff, keep serving cached prices |

Also check the body's `status` field. `"OK"` (or `"DELAYED"` for delayed data) indicates success. Do not rely on
an exact error-body schema. Key off the HTTP status code and log the body.

---

## 5. Java client options

| Option | Verdict |
|---|---|
| Official `massive-com/client-jvm` | Kotlin, auto-generated from OpenAPI daily, distributed via JitPack, no published Java examples. It pulls Kotlin and OkHttp into a Spring app. **Not used.** |
| Spring `RestClient` + Jackson records | We need about three endpoints. It's typed, testable with `MockRestServiceServer`, and adds no extra dependencies. **Chosen.** |
| `java.net.http.WebSocket` | JDK built-in, fine if/when we add streaming. |

### 5.1 DTOs (Java 21 records)

```java
package com.finallyapp.market.massive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SnapshotResponse(String status, Integer count, List<TickerSnapshot> tickers) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerSnapshot(
        String ticker,
        Double todaysChange,
        Double todaysChangePerc,
        Long updated,              // Unix nanoseconds
        Bar day,
        Bar min,
        Bar prevDay,
        LastTrade lastTrade) {}   // null unless plan includes trades

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bar(
        @JsonProperty("o") Double open,
        @JsonProperty("h") Double high,
        @JsonProperty("l") Double low,
        @JsonProperty("c") Double close,
        @JsonProperty("v") Double volume,
        @JsonProperty("vw") Double vwap,
        @JsonProperty("t") Long timestampMillis) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record LastTrade(
        @JsonProperty("p") Double price,
        @JsonProperty("s") Double size,
        @JsonProperty("t") Long timestampNanos) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregatesResponse(String status, Integer resultsCount, List<AggregateBar> results) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregateBar(
        @JsonProperty("T") String ticker,
        @JsonProperty("o") double open,
        @JsonProperty("h") double high,
        @JsonProperty("l") double low,
        @JsonProperty("c") double close,
        @JsonProperty("v") double volume,
        @JsonProperty("t") long timestampMillis) {}
```

(In real code, each public record goes in its own file.) Volume is `Double` because the API emits decimal
volumes in some places (fractional shares).

### 5.2 REST client

```java
package com.finallyapp.market.massive;

import com.finallyapp.market.massive.dto.*;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

public class MassiveClient {

    private final RestClient http;

    public MassiveClient(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.http = builder
                .baseUrl(baseUrl)                                   // https://api.massive.com
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    /** Current snapshot for many tickers in one call (Starter+). */
    public List<TickerSnapshot> snapshots(Collection<String> tickers) {
        SnapshotResponse body = http.get()
                .uri(u -> u.path("/v2/snapshot/locale/us/markets/stocks/tickers")
                        .queryParam("tickers", String.join(",", tickers))
                        .build())
                .retrieve()
                .body(SnapshotResponse.class);
        return body == null || body.tickers() == null ? List.of() : body.tickers();
    }

    /** End-of-day bars for every US stock on {@code date} (all plans). Empty on non-trading days. */
    public List<AggregateBar> groupedDaily(LocalDate date) {
        AggregatesResponse body = http.get()
                .uri("/v2/aggs/grouped/locale/us/market/stocks/{date}?adjusted=true", date)
                .retrieve()
                .body(AggregatesResponse.class);
        return body == null || body.results() == null ? List.of() : body.results();
    }

    /** Previous completed session's bar for one ticker (all plans). */
    public Optional<AggregateBar> previousClose(String ticker) {
        AggregatesResponse body = http.get()
                .uri("/v2/aggs/ticker/{ticker}/prev?adjusted=true", ticker)
                .retrieve()
                .body(AggregatesResponse.class);
        return body == null || body.results() == null || body.results().isEmpty()
                ? Optional.empty()
                : Optional.of(body.results().getFirst());
    }
}
```

### 5.3 Picking a price from a snapshot

```java
static Optional<Double> currentPrice(TickerSnapshot s) {
    return Stream.of(
                    s.lastTrade() == null ? null : s.lastTrade().price(),
                    s.min() == null ? null : s.min().close(),
                    s.day() == null ? null : s.day().close(),
                    s.prevDay() == null ? null : s.prevDay().close())
            .filter(p -> p != null && p > 0)
            .findFirst();
}
```

### 5.4 Last trading day with data (Basic-friendly EOD)

```java
/** Walks back from yesterday (US/Eastern) until a trading day with data is found; ≤ 5 calls. */
static Map<String, AggregateBar> latestEndOfDay(MassiveClient client, Set<String> wanted) {
    LocalDate day = LocalDate.now(ZoneId.of("America/New_York")).minusDays(1);
    for (int i = 0; i < 5; i++, day = day.minusDays(1)) {
        List<AggregateBar> bars = client.groupedDaily(day);
        if (!bars.isEmpty()) {
            return bars.stream()
                    .filter(b -> wanted.contains(b.ticker()))
                    .collect(Collectors.toMap(AggregateBar::ticker, b -> b));
        }
    }
    return Map.of();
}
```

### 5.5 Error handling with `RestClient`

```java
List<TickerSnapshot> result = http.get()
        .uri(/* … */)
        .retrieve()
        .onStatus(s -> s.value() == 403, (req, res) -> {
            throw new MassivePlanException("Endpoint not included in Massive plan: " + req.getURI().getPath());
        })
        .onStatus(s -> s.value() == 429, (req, res) -> {
            throw new MassiveRateLimitException();
        })
        .body(SnapshotResponse.class)
        .tickers();
```

### 5.6 WebSocket sketch (future streaming support)

```java
HttpClient.newHttpClient().newWebSocketBuilder()
        .buildAsync(URI.create("wss://delayed.massive.com/stocks"), new WebSocket.Listener() {
            private final StringBuilder buf = new StringBuilder();

            @Override public void onOpen(WebSocket ws) {
                ws.sendText("{\"action\":\"auth\",\"params\":\"" + apiKey + "\"}", true);
                WebSocket.Listener.super.onOpen(ws);
            }

            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    String frame = buf.toString();
                    buf.setLength(0);
                    // frame is a JSON array; on {"ev":"status","status":"auth_success"} send:
                    //   {"action":"subscribe","params":"AM.AAPL,AM.MSFT"}
                    // on {"ev":"AM",...} publish a price update from "c"
                    handleFrame(ws, frame);
                }
                return WebSocket.Listener.super.onText(ws, data, last);
            }
        });
```

---

## 6. Recommendations for this project

1. **Detect the plan at runtime, not via config.** Call the snapshot endpoint once at startup. A 200 response means
   live mode (poll the snapshot). A 403 means end-of-day mode (grouped daily). See `MARKET_INTERFACE.md`.
2. **Poll intervals:** snapshot every 15 s (configurable; delayed data changes at most once per minute per
   ticker for minute bars, so 15–60 s is plenty). EOD mode refreshes once per hour.
3. **Batch:** one snapshot call per poll for the whole watchlist (chunk at ~200 tickers to keep URLs short).
4. **Upper-case all tickers** before sending; Massive symbols are case-sensitive.
5. **Never log the API key.** Use the `Authorization` header rather than `?apiKey=`.

## Sources

* Docs index — <https://massive.com/docs/llms.txt>
* Full market snapshot — <https://massive.com/docs/rest/stocks/snapshots/full-market-snapshot.md>
* Single ticker snapshot — <https://massive.com/docs/rest/stocks/snapshots/single-ticker-snapshot.md>
* Unified snapshot — <https://massive.com/docs/rest/stocks/snapshots/unified-snapshot.md>
* Daily market summary (grouped daily) — <https://massive.com/docs/rest/stocks/aggregates/daily-market-summary.md>
* Previous day bar — <https://massive.com/docs/rest/stocks/aggregates/previous-day-bar.md>
* Custom bars — <https://massive.com/docs/rest/stocks/aggregates/custom-bars.md>
* Last trade — <https://massive.com/docs/rest/stocks/trades-quotes/last-trade.md>
* REST quickstart (auth, base URL) — <https://massive.com/docs/rest/quickstart.md>
* WebSocket trades / minute aggregates — <https://massive.com/docs/websocket/stocks/trades.md>, <https://massive.com/docs/websocket/stocks/aggregates-per-minute.md>
* Pricing — <https://massive.com/pricing>
* Basic plan data availability & rate limit — <https://massive.com/knowledge-base/article/on-the-free-basic-plan-when-can-i-pull-the-previous-trading-days-data>
* Official JVM client — <https://github.com/massive-com/client-jvm>
