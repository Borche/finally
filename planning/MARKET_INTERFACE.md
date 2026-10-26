# Market Data Interface — Unified Java API

The single API every part of the FinAlly backend uses to get stock prices. It is backed by **Massive** when the
`MASSIVE_API_KEY` environment variable is set (non-blank), and otherwise by the **simulator**
(`MARKET_SIMULATOR.md`). The rest of the application does not know or care which backend is running.

Massive endpoint details: `MASSIVE_API.md`.

---

## 1. Goals

1. **One interface, two implementations.** Trading logic, portfolio valuation and the API layer depend only on
   `MarketDataService`.
2. **Zero-config development.** With no API key the app starts with realistic, moving simulated prices.
3. **Works on every Massive plan.** Paid plans give live (delayed or real-time) prices. The free Basic plan falls
   back to end-of-day prices automatically.
4. **Reads never hit the network.** Callers read from an in-memory cache that a background poller or ticker
   keeps fresh. This avoids blocking trade execution and stays inside the free plan's 5 calls/min limit.
5. **Push updates.** Consumers (SSE/WebSocket to the frontend, alerts, limit orders) subscribe to price changes
   instead of polling.

## 2. Architecture

```
                         ┌──────────────────────────────────────────┐
  TradeService ─────┐    │             MarketDataService            │
  PortfolioService ─┼──▶ │  getPrice / getPrices / getEndOfDay      │
  PriceController ──┘    │  track / untrack / subscribe / mode      │
                         └──────────────┬───────────────────────────┘
                                        │ reads           ▲ notifies listeners
                                        ▼                 │
                                  ┌───────────┐  publish  │
                                  │ PriceCache│◀──────────┘
                                  └─────▲─────┘
                                        │ PriceSink.publish(quote)
                    ┌───────────────────┴────────────────────┐
         MarketDataSource (SPI, exactly one active)          │
          ┌─────────────────────────┐        ┌───────────────┴──────────┐
          │ MassiveMarketDataSource │        │ SimulatedMarketDataSource│
          │  polls REST snapshot    │        │  GBM ticks every 500 ms  │
          │  or grouped-daily (EOD) │        │                          │
          └─────────────────────────┘        └──────────────────────────┘
               chosen at startup by MarketDataConfig: MASSIVE_API_KEY set ? Massive : Simulator
```

* **`MarketDataService`** is the public facade, and the only type other packages may import (plus the value records).
* **`MarketDataSource`** is the internal SPI. Each implementation produces quotes and pushes them into a `PriceSink`.
* **`PriceCache`** implements `PriceSink`. It holds the latest quote per ticker and fans updates out to listeners.

## 3. Package layout

`finally` is a Java keyword and cannot be a package segment, so the root package is `com.finallyapp`.

```
backend/src/main/java/com/finallyapp/market/
├── MarketDataService.java          public facade (interface)
├── DefaultMarketDataService.java   facade impl: cache + source + listeners
├── PriceQuote.java                 record: latest price for a ticker
├── DailyBar.java                   record: one end-of-day OHLCV bar
├── MarketDataMode.java             enum: SIMULATED, MASSIVE_LIVE, MASSIVE_EOD
├── PriceListener.java              functional interface for push updates
├── Subscription.java               handle returned by subscribe(); close() to stop
├── UnknownTickerException.java
├── spi/
│   ├── MarketDataSource.java       SPI implemented by Massive and the simulator
│   └── PriceSink.java              where sources publish quotes
├── internal/
│   └── PriceCache.java             ConcurrentHashMap + CopyOnWriteArrayList listeners
├── config/
│   ├── MarketProperties.java       @ConfigurationProperties("market")
│   └── MarketDataConfig.java       chooses the source based on MASSIVE_API_KEY
├── massive/                        see MASSIVE_API.md
│   ├── MassiveClient.java
│   ├── MassiveMarketDataSource.java
│   ├── MassivePlanException.java
│   └── dto/…
└── simulator/                      see MARKET_SIMULATOR.md
    └── …
```

## 4. Public API

### 4.1 Value types

```java
package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Latest known price for one ticker. Money is BigDecimal (scale 4 for prices) so trade
 * arithmetic never sees binary floating-point rounding.
 */
public record PriceQuote(
        String ticker,
        BigDecimal price,
        BigDecimal previousClose,   // null if unknown
        Instant asOf,               // when the price was observed at the source, not when we fetched it
        MarketDataMode mode) {

    public BigDecimal change() {
        return previousClose == null ? BigDecimal.ZERO : price.subtract(previousClose);
    }

    public BigDecimal changePercent() {
        if (previousClose == null || previousClose.signum() == 0) return BigDecimal.ZERO;
        return change().multiply(BigDecimal.valueOf(100)).divide(previousClose, 2, RoundingMode.HALF_UP);
    }
}
```

```java
package com.finallyapp.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One completed trading session. */
public record DailyBar(
        String ticker,
        LocalDate date,             // trading date, America/New_York
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {}
```

```java
package com.finallyapp.market;

public enum MarketDataMode {
    /** Simulator — no API key configured. */
    SIMULATED,
    /** Massive snapshot polling (Starter+: 15-min delayed, Advanced+: real-time). */
    MASSIVE_LIVE,
    /** Massive end-of-day only (free Basic plan): price == last close, refreshed hourly. */
    MASSIVE_EOD
}
```

```java
package com.finallyapp.market;

@FunctionalInterface
public interface PriceListener {
    /** Called on a market-data thread. Must be fast and non-blocking; hand off slow work. */
    void onPrice(PriceQuote quote);
}

public interface Subscription extends AutoCloseable {
    @Override void close();   // no checked exception
}
```

### 4.2 `MarketDataService`

```java
package com.finallyapp.market;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MarketDataService {

    /** Latest cached quote; empty if the ticker is not tracked or has no price yet. Never blocks on I/O. */
    Optional<PriceQuote> getPrice(String ticker);

    /** Like {@link #getPrice} but throws {@link UnknownTickerException}. Convenient for trade execution. */
    PriceQuote requirePrice(String ticker);

    /** Latest cached quotes for the given tickers; tickers without a price are omitted. */
    Map<String, PriceQuote> getPrices(Collection<String> tickers);

    /** Snapshot of every tracked ticker. */
    Map<String, PriceQuote> getAllPrices();

    /**
     * Last completed trading session's bar per ticker. May do I/O (cached per trading date);
     * intended for daily P&L, not for hot paths.
     */
    Map<String, DailyBar> getEndOfDay(Collection<String> tickers);

    /** Start producing prices for a ticker (idempotent). The first quote arrives asynchronously. */
    void track(String ticker);

    /** Stop producing prices for a ticker unless it is still needed (see §6.3). */
    void untrack(String ticker);

    Set<String> trackedTickers();

    /** Register for every published quote. Close the returned subscription to stop. */
    Subscription subscribe(PriceListener listener);

    /** Which backend is active — expose it to the UI ("Simulated", "Delayed 15 min", "End of day"). */
    MarketDataMode mode();
}
```

Ticker normalisation happens in the facade: `ticker.trim().toUpperCase(Locale.ROOT)`. Validation uses
`^[A-Z][A-Z0-9.\-]{0,9}$`; anything else throws `IllegalArgumentException`.

### 4.3 SPI

```java
package com.finallyapp.market.spi;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** A producer of prices. Exactly one is active per application. */
public interface MarketDataSource extends AutoCloseable {

    /** Begin producing quotes for {@code tickers}; publish each one to {@code sink}. Called once. */
    void start(Set<String> tickers, PriceSink sink);

    void addTicker(String ticker);

    void removeTicker(String ticker);

    /** Last completed session per ticker. May block on I/O. */
    Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers);

    /** Current mode; may change at runtime (Massive LIVE → EOD on a 403). */
    MarketDataMode mode();

    @Override void close();   // stop threads / pollers
}
```

```java
package com.finallyapp.market.spi;

import com.finallyapp.market.PriceQuote;

@FunctionalInterface
public interface PriceSink {
    void publish(PriceQuote quote);
}
```

## 5. Choosing the implementation

### 5.1 Configuration

`backend/src/main/resources/application.yml`:

```yaml
spring:
  config:
    # The project keeps secrets in a root .env file; Spring Boot can import it as properties.
    import: optional:file:../.env[.properties]

market:
  default-tickers: AAPL,MSFT,GOOGL,AMZN,NVDA,META,TSLA,JPM,V,SPY
  massive:
    api-key: ${MASSIVE_API_KEY:}
    base-url: https://api.massive.com
    poll-interval: 15s          # snapshot polling in MASSIVE_LIVE mode
    eod-refresh-interval: 1h    # grouped-daily refresh in MASSIVE_EOD mode
    max-tickers-per-request: 200
  simulator:
    tick-interval: 500ms
    seed:                       # optional long, for reproducible runs/tests
    time-scale: 1.0             # >1 = more volatile/faster moving prices for demos
```

An environment variable `MASSIVE_API_KEY` always wins over `.env` (Spring's normal precedence).

```java
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
    }

    public record Simulator(
            @DefaultValue("500ms") Duration tickInterval,
            Long seed,
            @DefaultValue("1.0") double timeScale) {}
}
```

### 5.2 Wiring

```java
package com.finallyapp.market.config;

@Configuration
@EnableConfigurationProperties(MarketProperties.class)
class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

    @Bean(destroyMethod = "close")
    MarketDataSource marketDataSource(MarketProperties props, RestClient.Builder restClientBuilder) {
        if (props.massive().enabled()) {
            log.info("MASSIVE_API_KEY set — using Massive market data ({})", props.massive().baseUrl());
            var client = new MassiveClient(restClientBuilder, props.massive().baseUrl(), props.massive().apiKey());
            return new MassiveMarketDataSource(client, props.massive());
        }
        log.info("MASSIVE_API_KEY not set — using simulated market data");
        return new SimulatedMarketDataSource(props.simulator());
    }

    @Bean
    MarketDataService marketDataService(MarketDataSource source, MarketProperties props) {
        return new DefaultMarketDataService(source, props.defaultTickers());
    }
}
```

We use a plain `if` rather than `@ConditionalOnProperty`, because the latter treats an empty value
(`MASSIVE_API_KEY=` in `.env`) as "present". The `if` makes the rule explicit: **non-blank key ⇒ Massive**.

Tests can override the bean with `@TestConfiguration` (for example a simulator with a fixed seed) and stay independent of the
environment.

## 6. Behaviour

### 6.1 `DefaultMarketDataService`

```java
package com.finallyapp.market;

public class DefaultMarketDataService implements MarketDataService, SmartLifecycle {

    private final MarketDataSource source;
    private final PriceCache cache = new PriceCache();
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, Map<String, DailyBar>> eodCache = new ConcurrentHashMap<>();
    private volatile boolean running;

    public DefaultMarketDataService(MarketDataSource source, Collection<String> defaultTickers) {
        this.source = source;
        defaultTickers.stream().map(Tickers::normalize).forEach(tracked::add);
    }

    @Override public void start() {
        source.start(Set.copyOf(tracked), cache);
        running = true;
    }

    @Override public void stop() {
        running = false;
        source.close();
    }

    @Override public boolean isRunning() { return running; }

    @Override public Optional<PriceQuote> getPrice(String ticker) {
        return cache.get(Tickers.normalize(ticker));
    }

    @Override public PriceQuote requirePrice(String ticker) {
        return getPrice(ticker).orElseThrow(() -> new UnknownTickerException(ticker));
    }

    @Override public void track(String ticker) {
        String t = Tickers.normalize(ticker);
        if (tracked.add(t)) source.addTicker(t);
    }

    @Override public Subscription subscribe(PriceListener listener) {
        return cache.addListener(listener);
    }

    @Override public MarketDataMode mode() { return source.mode(); }

    // getPrices, getAllPrices, untrack, trackedTickers, getEndOfDay: straightforward, omitted
}
```

```java
package com.finallyapp.market.internal;

public final class PriceCache implements PriceSink {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final ConcurrentHashMap<String, PriceQuote> latest = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PriceListener> listeners = new CopyOnWriteArrayList<>();

    @Override public void publish(PriceQuote quote) {
        PriceQuote previous = latest.put(quote.ticker(), quote);
        if (quote.equals(previous)) return;                 // no change → no notification
        for (PriceListener l : listeners) {
            try {
                l.onPrice(quote);
            } catch (RuntimeException e) {                  // one bad listener must not break the feed
                log.warn("Price listener failed for {}", quote.ticker(), e);
            }
        }
    }

    public Optional<PriceQuote> get(String ticker) { return Optional.ofNullable(latest.get(ticker)); }

    public Map<String, PriceQuote> snapshot() { return Map.copyOf(latest); }

    public Subscription addListener(PriceListener l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }
}
```

### 6.2 `MassiveMarketDataSource`

A single scheduled thread (`Executors.newSingleThreadScheduledExecutor`, named `massive-poller`) runs a
two-mode state machine:

```
         start()
            │
            ▼
   ┌─────────────────┐  snapshot → 403 (plan lacks snapshots)   ┌────────────────┐
   │  MASSIVE_LIVE   │ ───────────────────────────────────────▶ │  MASSIVE_EOD   │
   │ poll snapshot   │                                          │ grouped daily  │
   │ every 15 s      │                                          │ every 1 h      │
   └─────────────────┘                                          └────────────────┘
        │ 401 → log error, stop polling (bad key; never fall back to simulator)
        │ 429 / 5xx / IOException → keep cached prices, back off (×2, max 5 min), retry
```

* **LIVE poll:** one `GET /v2/snapshot/...?tickers=…` per poll, chunked by `max-tickers-per-request`.
  Price uses the fallback chain `lastTrade.p → min.c → day.c → prevDay.c`. `previousClose` = `prevDay.c`.
  `asOf` = `updated` (ns → `Instant`).
* **EOD poll:** `groupedDaily(d)` for the most recent trading day `d` that has data (walk back from yesterday
  ET, at most 5 calls), plus the trading day before it for `previousClose`. That is 2–6 calls per refresh,
  well inside 5 calls/min when spread out (sleep 13 s between calls).
  `price` = close of `d`, `asOf` = 16:00 ET on `d`.
* **`addTicker`** triggers an immediate out-of-band poll so a new ticker gets a price within seconds (in EOD
  mode the last grouped-daily result is kept in memory, so new tickers are answered from it with no API call).
* **`fetchEndOfDay`** reuses the grouped-daily logic in both modes and is cached per trading date in the facade.
* **No silent fallback to the simulator.** If a key is configured, the app shows real data or stale real data,
  never invented prices. The UI can show staleness from `PriceQuote.asOf`.
* Double → BigDecimal conversion: `BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP)`.

### 6.3 Which tickers are tracked

`tracked` = configured default tickers ∪ tickers that appear in any user's watchlist or holdings. The
portfolio and watchlist services call `track` when a ticker is added. `untrack` is called only when no watchlist
or position references the ticker any more. On startup, a `ApplicationRunner` in the portfolio module tracks every
ticker it finds in the DB.

### 6.4 Threading and consistency guarantees

* All reads are lock-free (`ConcurrentHashMap`) and never block on I/O, except `getEndOfDay` on a cache miss.
* Quotes for one ticker are published in order by a single source thread. Listeners see a consistent
  per-ticker sequence.
* Listeners run on the source thread. A slow listener delays the next update, so SSE broadcasting must enqueue
  work to its own executor.

## 7. Using the API

### 7.1 Trade execution

```java
@Service
class TradeService {
    private final MarketDataService market;

    Trade buy(UserId user, String ticker, int qty) {
        PriceQuote quote = market.requirePrice(ticker);            // in-memory, no I/O
        BigDecimal cost = quote.price().multiply(BigDecimal.valueOf(qty));
        // … check cash, persist trade at quote.price(), record quote.asOf() for audit
    }
}
```

### 7.2 Pushing prices to the frontend (frontend-agnostic SSE)

```java
@RestController
@RequestMapping("/api/prices")
class PriceController {
    private final MarketDataService market;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping
    Map<String, PriceQuote> prices(@RequestParam(required = false) List<String> tickers) {
        return tickers == null ? market.getAllPrices() : market.getPrices(tickers);
    }

    @GetMapping("/mode")
    MarketDataMode mode() { return market.mode(); }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);                 // no timeout
        market.getAllPrices().values().forEach(q -> send(emitter, q));  // initial state
        Subscription sub = market.subscribe(q -> sseExecutor.execute(() -> send(emitter, q)));
        emitter.onCompletion(sub::close);
        emitter.onTimeout(sub::close);
        emitter.onError(e -> sub.close());
        return emitter;
    }

    private static void send(SseEmitter emitter, PriceQuote q) {
        try {
            emitter.send(SseEmitter.event().name("price").data(q));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
```

Event payload (JSON):

```json
{ "ticker": "AAPL", "price": 231.4512, "previousClose": 229.87, "asOf": "2026-10-26T14:31:07.512Z", "mode": "SIMULATED" }
```

`change`/`changePercent` are derived methods, not record components, so Jackson does not serialise them by default.
The frontend can compute them, or a `PriceDto` can add them in the web layer. This is a web-layer decision for
when the frontend stack is chosen.

## 8. Testing strategy

| Test | What |
|---|---|
| `MarketDataSourceContractTest` (abstract) | Shared behaviour both implementations must satisfy: `start` publishes a quote for every ticker; `addTicker` produces a quote; `removeTicker` stops quotes; `close` stops threads; prices > 0; tickers upper-case. |
| `SimulatedMarketDataSourceTest` | Extends the contract with a fixed seed and a fake clock (see `MARKET_SIMULATOR.md`). |
| `MassiveMarketDataSourceTest` | Extends the contract with `MockRestServiceServer` and canned JSON from `MASSIVE_API.md`. Covers the price fallback chain, LIVE→EOD switch on 403, backoff on 429, weekend walk-back. |
| `MarketDataConfigTest` | `ApplicationContextRunner`: blank or missing key ⇒ simulator; key ⇒ Massive (no network, client mocked). |
| `PriceCacheTest` | Listener isolation (a throwing listener doesn't block others), duplicate suppression, unsubscribe. |

No test may hit the real Massive API. An optional `@Tag("live")` smoke test runs only when
`MASSIVE_API_KEY` is present in CI.

## 9. Extension points (not in v1)

* **`MassiveWebSocketSource`**: a third `MarketDataSource` that uses `AM.*` streaming (sketch in `MASSIVE_API.md`
  §5.6). It could be selected with `market.massive.transport=websocket`. Nothing above changes.
* **Intraday history for charts**: add `List<Bar> getHistory(String ticker, Range range)` to the facade, backed
  by Massive custom bars or the simulator's generated history.
* **Market hours**: `MarketClock` (open/closed/pre/post) so the UI and trade rules can respect sessions.
