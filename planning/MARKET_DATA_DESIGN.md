# Market Data Backend — Detailed Design

This document turns the three planning notes into one design that can be implemented directly:

| Source document | What it contributed |
|---|---|
| `MARKET_INTERFACE.md` | The unified API (`MarketDataService`), the SPI, caching, wiring, threading rules |
| `MARKET_SIMULATOR.md` | GBM + factor + jump price model, ticker profiles, history backfill |
| `MASSIVE_API.md` | Endpoints, DTOs, plan limits, error codes, LIVE/EOD strategy |

It has complete, compilable-in-spirit Java 21 / Spring Boot 3.3+ code for every class in the
`com.finallyapp.market` package, plus configuration, a web layer, tests and worked examples. Where this document
refines or differs from the planning notes, §12 lists the change and the reason.

---

## Table of contents

1. [Overview](#1-overview)
2. [Package layout and build](#2-package-layout-and-build)
3. [Public API](#3-public-api)
4. [SPI](#4-spi)
5. [Facade and cache](#5-facade-and-cache)
6. [Configuration and wiring](#6-configuration-and-wiring)
7. [Simulator](#7-simulator)
8. [Massive implementation](#8-massive-implementation)
9. [Web layer: REST + SSE](#9-web-layer-rest--sse)
10. [Testing](#10-testing)
11. [Worked examples](#11-worked-examples)
12. [Design decisions and refinements](#12-design-decisions-and-refinements)
13. [Implementation order](#13-implementation-order)

---

## 1. Overview

```
 TradeService ─┐                    ┌───────────────────────────────────────────┐
 Portfolio ────┼─ getPrice/track ─▶ │ DefaultMarketDataService (facade)         │
 PriceStream ──┘   subscribe        │  tracked set · EOD cache · ticker filter  │
                                    └──────┬─────────────────────────▲──────────┘
                                   reads   │                         │ PriceSink (filtered)
                                           ▼                         │
                                    ┌─────────────┐   publish        │
                                    │ PriceCache  │◀─────────────────┤
                                    │ latest+fan-out                 │
                                    └─────────────┘                  │
                      exactly one MarketDataSource, chosen by MarketDataConfig
              ┌──────────────────────────────┐        ┌──────────────┴───────────────┐
              │ SimulatedMarketDataSource    │        │ MassiveMarketDataSource      │
              │ thread "market-sim", 500 ms  │        │ thread "massive-poller"      │
              │ GBM + market/sector + jumps  │        │ LIVE: snapshot every 15 s    │
              └──────────────────────────────┘        │ EOD : grouped daily, hourly  │
                                                      └──────────────────────────────┘
```

The rules from the planning documents that every class below enforces:

* **One facade.** Other packages import only `com.finallyapp.market.*` (the facade and its value types).
* **Reads never touch the network.** `getPrice`/`getPrices`/`getAllPrices` read a `ConcurrentHashMap`.
* **Non-blank `MASSIVE_API_KEY` ⇒ Massive, otherwise simulator.** If a key is set, the app never invents prices.
* **Single writer per source.** Each source mutates its state on one dedicated thread.
* **Money is `BigDecimal`, scale 4.**

## 2. Package layout and build

```
backend/
├── pom.xml
└── src/
    ├── main/java/com/finallyapp/market/
    │   ├── MarketDataService.java          public facade
    │   ├── DefaultMarketDataService.java   facade impl (SmartLifecycle)
    │   ├── PriceQuote.java  DailyBar.java  MarketDataMode.java
    │   ├── PriceListener.java  Subscription.java  UnknownTickerException.java
    │   ├── Tickers.java                    normalisation + validation
    │   ├── spi/        MarketDataSource.java, PriceSink.java
    │   ├── internal/   PriceCache.java, Prices.java
    │   ├── config/     MarketProperties.java, MarketDataConfig.java
    │   ├── simulator/  SimulatedMarketDataSource, PriceModel, TickerState, TickerProfile,
    │   │               TickerProfiles, Sector, HistoryGenerator, SimulationParameters
    │   ├── massive/    MassiveClient, MassiveMarketDataSource, SnapshotPrices, Sleeper,
    │   │               MassiveApiException (+ Auth/Plan/RateLimit subclasses), dto/…
    │   └── web/        PriceController, PriceStream, PriceDto
    ├── main/resources/
    │   ├── application.yml
    │   └── simulator/tickers.csv
    └── test/java/com/finallyapp/market/…   (see §10)
```

`finally` is a Java keyword, so the root package is `com.finallyapp`.

### 2.1 Maven dependencies

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <!-- RestClient, Jackson, SseEmitter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>  <!-- JUnit 5, AssertJ, MockRestServiceServer -->
        <scope>test</scope>
    </dependency>
</dependencies>
```

No Massive SDK is used (`MASSIVE_API.md` §5): three endpoints over Spring `RestClient` are enough.

---

## 3. Public API

Package `com.finallyapp.market`. These are the only types other modules may use.

### 3.1 Value types

```java
package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Latest known price for one ticker. Prices are BigDecimal with scale 4.
 *
 * @param previousClose close of the previous session, or null if unknown
 * @param asOf          when the price was observed at the source (not when we fetched it)
 */
public record PriceQuote(
        String ticker,
        BigDecimal price,
        BigDecimal previousClose,
        Instant asOf,
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

/** One completed trading session. {@code date} is the America/New_York trading date. */
public record DailyBar(
        String ticker,
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {}
```

```java
package com.finallyapp.market;

public enum MarketDataMode {
    /** Simulator: no API key configured. */
    SIMULATED("Simulated"),
    /** Massive snapshot polling (Starter+: 15-min delayed, Advanced+: real-time). */
    MASSIVE_LIVE("Live"),
    /** Massive end-of-day only (free Basic plan): price == last close. */
    MASSIVE_EOD("End of day");

    private final String label;

    MarketDataMode(String label) { this.label = label; }

    /** Short human-readable label for the UI. */
    public String label() { return label; }
}
```

```java
package com.finallyapp.market;

@FunctionalInterface
public interface PriceListener {
    /** Called on a market-data thread. Must be fast and non-blocking; hand off slow work. */
    void onPrice(PriceQuote quote);
}
```

```java
package com.finallyapp.market;

/** Handle returned by {@link MarketDataService#subscribe}. Closing it is idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
```

```java
package com.finallyapp.market;

public class UnknownTickerException extends RuntimeException {
    private final String ticker;

    public UnknownTickerException(String ticker) {
        super("No price available for ticker " + ticker);
        this.ticker = ticker;
    }

    public String ticker() { return ticker; }
}
```

### 3.2 Ticker normalisation

```java
package com.finallyapp.market;

import java.util.Locale;
import java.util.regex.Pattern;

/** All tickers are normalised once, at the facade boundary. Sources only ever see normalised tickers. */
public final class Tickers {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private Tickers() {}

    /** Trims and upper-cases; throws IllegalArgumentException for anything that cannot be a US ticker. */
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Ticker must not be null");
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(t).matches()) throw new IllegalArgumentException("Invalid ticker: '" + raw + "'");
        return t;
    }
}
```

### 3.3 `MarketDataService`

```java
package com.finallyapp.market;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MarketDataService {

    /** Latest cached quote; empty if the ticker is not tracked or has no price yet. Never blocks on I/O. */
    Optional<PriceQuote> getPrice(String ticker);

    /** Like {@link #getPrice} but throws {@link UnknownTickerException}. Use for trade execution. */
    PriceQuote requirePrice(String ticker);

    /** Latest cached quotes for the given tickers, in request order; tickers without a price are omitted. */
    Map<String, PriceQuote> getPrices(Collection<String> tickers);

    /** Snapshot of every tracked ticker that has a price, sorted by ticker. */
    Map<String, PriceQuote> getAllPrices();

    /** Last completed session per ticker. May do I/O on a cache miss (cached per ET date). Not for hot paths. */
    Map<String, DailyBar> getEndOfDay(Collection<String> tickers);

    /** Start producing prices for a ticker (idempotent). The first quote arrives asynchronously. */
    void track(String ticker);

    /** Stop producing prices for a ticker. Default tickers are never untracked. */
    void untrack(String ticker);

    Set<String> trackedTickers();

    /** Register for every published quote. Close the returned subscription to stop. */
    Subscription subscribe(PriceListener listener);

    /** Which backend is active (may change at runtime from MASSIVE_LIVE to MASSIVE_EOD). */
    MarketDataMode mode();
}
```

---

## 4. SPI

Package `com.finallyapp.market.spi`. Implemented by the two sources, consumed only by the facade.

```java
package com.finallyapp.market.spi;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** A producer of prices. Exactly one is active per application. Tickers passed in are already normalised. */
public interface MarketDataSource extends AutoCloseable {

    /** Begin producing quotes for {@code tickers}; publish each one to {@code sink}. Called once. */
    void start(Set<String> tickers, PriceSink sink);

    /** Called only after {@link #start}. */
    void addTicker(String ticker);

    void removeTicker(String ticker);

    /** Last completed session per ticker. May block on I/O. Thread-safe. */
    Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers);

    /** Current mode; may change at runtime. */
    MarketDataMode mode();

    /** Stop threads. Must be idempotent (Spring may call it twice). */
    @Override
    void close();
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

---

## 5. Facade and cache

### 5.1 `Prices` (shared rounding helpers)

```java
package com.finallyapp.market.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Prices {

    public static final int SCALE = 4;

    private Prices() {}

    /** Exact-as-possible conversion of an API double. Used for Massive prices. */
    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Rounds to a realistic tick: cents at or above $1, $0.0001 below. Result always has scale 4. */
    public static BigDecimal toTick(double value) {
        int places = value >= 1.0 ? 2 : 4;
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).setScale(SCALE);
    }
}
```

### 5.2 `PriceCache`

```java
package com.finallyapp.market.internal;

import com.finallyapp.market.PriceListener;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.Subscription;
import com.finallyapp.market.spi.PriceSink;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Latest quote per ticker plus listener fan-out. Lock-free reads; publish is called from one source thread. */
public final class PriceCache implements PriceSink {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final ConcurrentHashMap<String, PriceQuote> latest = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PriceListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(PriceQuote quote) {
        PriceQuote previous = latest.put(quote.ticker(), quote);
        if (quote.equals(previous)) return;                 // identical re-poll: no notification
        for (PriceListener l : listeners) {
            try {
                l.onPrice(quote);
            } catch (RuntimeException e) {                  // one bad listener must not break the feed
                log.warn("Price listener failed for {}", quote.ticker(), e);
            }
        }
    }

    public Optional<PriceQuote> get(String ticker) {
        return Optional.ofNullable(latest.get(ticker));
    }

    public Map<String, PriceQuote> snapshot() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(latest));
    }

    public void remove(String ticker) {
        latest.remove(ticker);
    }

    public Subscription addListener(PriceListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
```

### 5.3 `DefaultMarketDataService`

```java
package com.finallyapp.market;

import com.finallyapp.market.internal.PriceCache;
import com.finallyapp.market.spi.MarketDataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.context.SmartLifecycle;

public class DefaultMarketDataService implements MarketDataService, SmartLifecycle {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final MarketDataSource source;
    private final Set<String> defaultTickers;
    private final Clock clock;
    private final PriceCache cache = new PriceCache();
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, Map<String, DailyBar>> eodCache = new ConcurrentHashMap<>();
    private volatile boolean running;

    public DefaultMarketDataService(MarketDataSource source, Collection<String> defaultTickers) {
        this(source, defaultTickers, Clock.systemUTC());
    }

    DefaultMarketDataService(MarketDataSource source, Collection<String> defaultTickers, Clock clock) {
        this.source = source;
        this.clock = clock;
        this.defaultTickers = defaultTickers.stream().map(Tickers::normalize).collect(Collectors.toUnmodifiableSet());
        this.tracked.addAll(this.defaultTickers);
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        // The sink drops quotes for tickers untracked while a poll was in flight, so they don't reappear.
        source.start(Set.copyOf(tracked), quote -> {
            if (tracked.contains(quote.ticker())) cache.publish(quote);
        });
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        source.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- reads (never block) -----------------------------------------------------------------

    @Override
    public Optional<PriceQuote> getPrice(String ticker) {
        return cache.get(Tickers.normalize(ticker));
    }

    @Override
    public PriceQuote requirePrice(String ticker) {
        String t = Tickers.normalize(ticker);
        return cache.get(t).orElseThrow(() -> new UnknownTickerException(t));
    }

    @Override
    public Map<String, PriceQuote> getPrices(Collection<String> tickers) {
        Map<String, PriceQuote> out = new LinkedHashMap<>();
        for (String raw : tickers) {
            String t = Tickers.normalize(raw);
            cache.get(t).ifPresent(q -> out.put(t, q));
        }
        return out;
    }

    @Override
    public Map<String, PriceQuote> getAllPrices() {
        return cache.snapshot();
    }

    // ---- end of day --------------------------------------------------------------------------

    @Override
    public Map<String, DailyBar> getEndOfDay(Collection<String> tickers) {
        List<String> wanted = tickers.stream().map(Tickers::normalize).distinct().toList();
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        Map<String, DailyBar> day = eodCache.computeIfAbsent(today, d -> new ConcurrentHashMap<>());
        eodCache.keySet().removeIf(d -> !d.equals(today));            // keep only today's entry

        List<String> missing = wanted.stream().filter(t -> !day.containsKey(t)).toList();
        if (!missing.isEmpty()) {
            day.putAll(source.fetchEndOfDay(missing));
        }
        Map<String, DailyBar> out = new LinkedHashMap<>();
        wanted.forEach(t -> { DailyBar b = day.get(t); if (b != null) out.put(t, b); });
        return out;
    }

    // ---- tracking ----------------------------------------------------------------------------

    @Override
    public synchronized void track(String ticker) {
        String t = Tickers.normalize(ticker);
        if (tracked.add(t) && running) source.addTicker(t);   // before start(): picked up by start()
    }

    @Override
    public synchronized void untrack(String ticker) {
        String t = Tickers.normalize(ticker);
        if (defaultTickers.contains(t) || !tracked.remove(t)) return;
        if (running) source.removeTicker(t);
        cache.remove(t);
    }

    @Override
    public Set<String> trackedTickers() {
        return Set.copyOf(tracked);
    }

    // ---- push + mode -------------------------------------------------------------------------

    @Override
    public Subscription subscribe(PriceListener listener) {
        return cache.addListener(listener);
    }

    @Override
    public MarketDataMode mode() {
        return source.mode();
    }
}
```

Notes:

* `track`/`untrack`/`start` are `synchronized` so a `track` that happens while `start` runs is never lost.
  Reads are not synchronized.
* Deciding *whether* a ticker is still needed (any watchlist or position references it) belongs to the
  portfolio/watchlist services. They call `untrack` only when the last reference goes away. The facade itself
  only protects the default tickers.
* `SmartLifecycle` default phase is `Integer.MAX_VALUE`: the source starts after the web server and stops
  before it. Requests in that short window get an empty `Optional`, which callers already handle.

### 5.4 Tracking tickers from the database at startup

Lives in the portfolio module, not in `market`:

```java
@Component
class TrackPortfolioTickers implements ApplicationRunner {

    private final MarketDataService market;
    private final WatchlistRepository watchlists;
    private final PositionRepository positions;

    TrackPortfolioTickers(MarketDataService market, WatchlistRepository watchlists, PositionRepository positions) {
        this.market = market;
        this.watchlists = watchlists;
        this.positions = positions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Stream.concat(watchlists.findDistinctTickers().stream(), positions.findDistinctTickers().stream())
                .distinct()
                .forEach(market::track);
    }
}
```

---

## 6. Configuration and wiring

### 6.1 `application.yml`

```yaml
spring:
  config:
    # Secrets live in the repo-root .env; import it whether the app runs from the root or from backend/.
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]

market:
  default-tickers: AAPL,MSFT,GOOGL,AMZN,NVDA,META,TSLA,JPM,V,SPY
  massive:
    api-key: ${MASSIVE_API_KEY:}
    base-url: https://api.massive.com
    poll-interval: 15s
    eod-refresh-interval: 1h
    max-tickers-per-request: 200
  simulator:
    # Explicit placeholders: relaxed binding would map MARKET_SIMULATOR_TICK_INTERVAL to
    # market.simulator.tick.interval, not market.simulator.tick-interval.
    tick-interval: ${MARKET_SIMULATOR_TICK_INTERVAL:500ms}
    time-scale: ${MARKET_SIMULATOR_TIME_SCALE:1.0}
    seed: ${MARKET_SIMULATOR_SEED:}
    market-beta: 0.5
    sector-beta: 0.4
    mean-time-between-jumps: 2h
    jump-sigma: 0.02
    market-jump-sigma: 0.015
    history-days: 260
```

A real environment variable always overrides `.env` (Spring's normal precedence). An empty `seed` binds to `null`
(random seed).

### 6.2 `MarketProperties`

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
```

### 6.3 `MarketDataConfig`

```java
package com.finallyapp.market.config;

import com.finallyapp.market.DefaultMarketDataService;
import com.finallyapp.market.MarketDataService;
import com.finallyapp.market.massive.MassiveClient;
import com.finallyapp.market.massive.MassiveMarketDataSource;
import com.finallyapp.market.simulator.SimulatedMarketDataSource;
import com.finallyapp.market.spi.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MarketProperties.class)
class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

    @Bean(destroyMethod = "close")
    MarketDataSource marketDataSource(MarketProperties props, RestClient.Builder restClientBuilder) {
        if (props.massive().enabled()) {
            log.info("MASSIVE_API_KEY set: using Massive market data ({})", props.massive().baseUrl());
            var client = new MassiveClient(restClientBuilder, props.massive().baseUrl(), props.massive().apiKey());
            return new MassiveMarketDataSource(client, props.massive());
        }
        log.info("MASSIVE_API_KEY not set: using simulated market data");
        return new SimulatedMarketDataSource(props.simulator());
    }

    @Bean
    MarketDataService marketDataService(MarketDataSource source, MarketProperties props) {
        return new DefaultMarketDataService(source, props.defaultTickers());
    }
}
```

A plain `if` instead of `@ConditionalOnProperty`, because the latter treats `MASSIVE_API_KEY=` (empty, as in
`.env.example`) as present.

---

## 7. Simulator

Package `com.finallyapp.market.simulator`. Only `SimulatedMarketDataSource` is public. The model is in
`MARKET_SIMULATOR.md` §2; the formulas are repeated next to the code that implements them.

### 7.1 `tickers.csv`

`src/main/resources/simulator/tickers.csv`:

```csv
ticker,price,drift,vol,sector
AAPL,230,0.08,0.25,TECH
MSFT,420,0.09,0.24,TECH
GOOGL,170,0.08,0.28,TECH
AMZN,190,0.10,0.32,CONSUMER
NVDA,130,0.15,0.50,TECH
META,560,0.10,0.35,TECH
TSLA,250,0.05,0.60,CONSUMER
JPM,210,0.06,0.22,FINANCE
V,280,0.07,0.20,FINANCE
JNJ,155,0.04,0.16,HEALTH
XOM,115,0.04,0.25,ENERGY
SPY,575,0.07,0.16,INDEX
QQQ,490,0.09,0.21,INDEX
```

### 7.2 `Sector`, `TickerProfile`

```java
package com.finallyapp.market.simulator;

import java.util.List;

enum Sector {
    TECH, FINANCE, HEALTH, ENERGY, CONSUMER, INDUSTRIAL, INDEX;

    /** Sectors that synthesized (unknown) tickers can be assigned to. */
    static final List<Sector> STOCK_SECTORS = List.of(TECH, FINANCE, HEALTH, ENERGY, CONSUMER, INDUSTRIAL);
}
```

```java
package com.finallyapp.market.simulator;

/** Immutable per-ticker parameters. */
record TickerProfile(
        String ticker,
        double initialPrice,
        double annualDrift,        // μ
        double annualVolatility,   // σ
        Sector sector,
        double marketBeta,         // β_m
        double sectorBeta,         // β_s
        long averageVolume) {      // shares/day, for synthetic bars

    TickerProfile {
        if (marketBeta * marketBeta + sectorBeta * sectorBeta > 1.0) {
            throw new IllegalArgumentException("β_m² + β_s² must be ≤ 1 for " + ticker);
        }
    }
}
```

### 7.3 `SimulationParameters`

```java
package com.finallyapp.market.simulator;

import com.finallyapp.market.config.MarketProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/** Per-tick constants derived once from configuration. */
record SimulationParameters(
        Duration tickInterval,
        double dt,                             // years of trading time per tick
        double marketBeta,
        double sectorBeta,
        double jumpProbabilityPerTick,
        double jumpSigma,
        double marketJumpProbabilityPerTick,
        double marketJumpSigma,
        int historyDays,
        long seed) {

    private static final double TRADING_SECONDS_PER_DAY = 6.5 * 3600;
    private static final double TRADING_SECONDS_PER_YEAR = 252 * TRADING_SECONDS_PER_DAY;

    static SimulationParameters from(MarketProperties.Simulator c) {
        // Δt = tickInterval × timeScale / (252 × 6.5 h)   ≈ 8.48e-8 for 500 ms at timeScale 1
        double simSecondsPerTick = c.tickInterval().toMillis() / 1000.0 * c.timeScale();
        long seed = c.seed() != null ? c.seed() : ThreadLocalRandom.current().nextLong();
        return new SimulationParameters(
                c.tickInterval(),
                simSecondsPerTick / TRADING_SECONDS_PER_YEAR,
                c.marketBeta(),
                c.sectorBeta(),
                Math.min(1.0, simSecondsPerTick / c.meanTimeBetweenJumps().toSeconds()),
                c.jumpSigma(),
                Math.min(1.0, simSecondsPerTick / TRADING_SECONDS_PER_DAY),   // ≈ one market event per sim day
                c.marketJumpSigma(),
                c.historyDays(),
                seed);
    }

    static RandomGenerator newRng(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }
}
```

### 7.4 `TickerProfiles`

```java
package com.finallyapp.market.simulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.core.io.ClassPathResource;

/** Built-in profiles from tickers.csv; deterministic synthetic profiles for everything else. */
final class TickerProfiles {

    private final SimulationParameters params;
    private final Map<String, TickerProfile> builtIn;

    TickerProfiles(SimulationParameters params) {
        this.params = params;
        this.builtIn = load("simulator/tickers.csv", params);
    }

    TickerProfile forTicker(String ticker) {
        TickerProfile p = builtIn.get(ticker);
        return p != null ? p : synthesize(ticker, params);
    }

    /** Stable across restarts and machines: String.hashCode is specified by the JLS. */
    static TickerProfile synthesize(String ticker, SimulationParameters params) {
        var rng = new SplittableRandom(ticker.hashCode() * 0x9E3779B97F4A7C15L);
        double price = Math.exp(rng.nextDouble(Math.log(10), Math.log(500)));   // log-uniform $10–$500
        double vol = rng.nextDouble(0.20, 0.60);
        Sector sector = Sector.STOCK_SECTORS.get(rng.nextInt(Sector.STOCK_SECTORS.size()));
        return profile(ticker, Math.round(price * 100) / 100.0, 0.07, vol, sector, params);
    }

    private static TickerProfile profile(String ticker, double price, double drift, double vol,
                                         Sector sector, SimulationParameters p) {
        boolean index = sector == Sector.INDEX;                  // index ETFs track the market factor
        return new TickerProfile(ticker, price, drift, vol, sector,
                index ? 0.95 : p.marketBeta(),
                index ? 0.0 : p.sectorBeta(),
                Math.max(100_000L, Math.round(5e9 / price)));    // ~$5B/day traded: plausible large cap
    }

    private static Map<String, TickerProfile> load(String path, SimulationParameters params) {
        Map<String, TickerProfile> out = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().skip(1).filter(l -> !l.isBlank()).forEach(line -> {
                String[] f = line.split(",");
                String ticker = f[0].trim();
                out.put(ticker, profile(ticker, Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), Sector.valueOf(f[4].trim()), params));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return Map.copyOf(out);
    }
}
```

### 7.5 `PriceModel`

```java
package com.finallyapp.market.simulator;

import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GBM with market/sector factors and Poisson jumps. Stateless: all randomness comes from the caller's RNG.
 *
 *   Z_i       = β_m·Z_market + β_s·Z_sector + √(1 − β_m² − β_s²)·Z_idio        (still N(0,1))
 *   log-ret   = (μ − σ²/2)·Δt + σ·√Δt·Z_i + β_m·J_market + [J_idio with prob p_jump]
 *   S(t+Δt)   = max(S(t)·exp(log-ret), 0.01)
 */
final class PriceModel {

    private static final Logger log = LoggerFactory.getLogger(PriceModel.class);

    private final SimulationParameters p;
    private final double sqrtDt;

    PriceModel(SimulationParameters p) {
        this.p = p;
        this.sqrtDt = Math.sqrt(p.dt());
    }

    double step(double price, TickerProfile prof, double zMarket, double zSector,
                double marketJump, RandomGenerator rng) {
        double bm = prof.marketBeta();
        double bs = prof.sectorBeta();
        double z = bm * zMarket + bs * zSector + Math.sqrt(1 - bm * bm - bs * bs) * rng.nextGaussian();

        double sigma = prof.annualVolatility();
        double logReturn = (prof.annualDrift() - 0.5 * sigma * sigma) * p.dt() + sigma * sqrtDt * z
                + bm * marketJump;                                   // 0 on almost every tick

        if (rng.nextDouble() < p.jumpProbabilityPerTick()) {
            double jump = rng.nextGaussian() * p.jumpSigma();
            logReturn += jump;
            if (log.isDebugEnabled()) {
                log.debug("SIM EVENT: {} jumped {}%", prof.ticker(), String.format("%+.1f", Math.expm1(jump) * 100));
            }
        }
        return Math.max(price * Math.exp(logReturn), 0.01);
    }
}
```

### 7.6 `HistoryGenerator`

Walks **backwards** from the initial price to generate daily closes, then builds each day's OHLC from 78
five-minute sub-steps pinned to that day's open and close (a Brownian bridge), so `low ≤ open, close ≤ high`
always holds.

```java
package com.finallyapp.market.simulator;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.internal.Prices;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/** Pure and deterministic: same (seed, ticker, today) ⇒ same bars. */
final class HistoryGenerator {

    private static final int STEPS_PER_DAY = 78;                 // 6.5 h of 5-minute bars
    private static final double DAY = 1.0 / 252;

    private final SimulationParameters params;

    HistoryGenerator(SimulationParameters params) {
        this.params = params;
    }

    /** {@code historyDays} completed weekday sessions before {@code today}; last close == initialPrice. */
    List<DailyBar> backfill(TickerProfile prof, LocalDate today) {
        RandomGenerator rng = SimulationParameters.newRng(params.seed() ^ prof.ticker().hashCode());
        List<LocalDate> dates = tradingDaysBefore(today, params.historyDays());
        int n = dates.size();
        double mu = prof.annualDrift();
        double sigma = prof.annualVolatility();

        // 1. Closes, walking backwards: C[i-1] = C[i] / exp(daily log-return)
        double[] close = new double[n];
        close[n - 1] = prof.initialPrice();
        for (int i = n - 1; i > 0; i--) {
            double r = (mu - 0.5 * sigma * sigma) * DAY + sigma * Math.sqrt(DAY) * rng.nextGaussian();
            close[i - 1] = close[i] / Math.exp(r);
        }

        // 2. Intraday path per day, bridged from open to close.
        double subDt = DAY / STEPS_PER_DAY;
        double[] path = new double[STEPS_PER_DAY + 1];
        List<DailyBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double prevClose = i == 0 ? close[0] : close[i - 1];
            double open = prevClose * Math.exp(rng.nextGaussian() * 0.003);   // small overnight gap
            path[0] = Math.log(open);
            for (int k = 1; k <= STEPS_PER_DAY; k++) {
                path[k] = path[k - 1] + (mu - 0.5 * sigma * sigma) * subDt
                        + sigma * Math.sqrt(subDt) * rng.nextGaussian();
            }
            double error = Math.log(close[i]) - path[STEPS_PER_DAY];
            double high = open, low = open;
            for (int k = 1; k <= STEPS_PER_DAY; k++) {
                double px = Math.exp(path[k] + error * k / STEPS_PER_DAY);
                high = Math.max(high, px);
                low = Math.min(low, px);
            }
            long volume = Math.round(prof.averageVolume() * Math.exp(0.3 * rng.nextGaussian() - 0.045));
            bars.add(new DailyBar(prof.ticker(), dates.get(i), Prices.toTick(open), Prices.toTick(high),
                    Prices.toTick(low), Prices.toTick(close[i]), volume));
        }
        return bars;
    }

    /** Oldest → newest weekdays strictly before {@code today}. Holidays are ignored. */
    static List<LocalDate> tradingDaysBefore(LocalDate today, int count) {
        Deque<LocalDate> out = new ArrayDeque<>(count);
        for (LocalDate d = today.minusDays(1); out.size() < count; d = d.minusDays(1)) {
            if (isWeekday(d)) out.addFirst(d);
        }
        return List.copyOf(out);
    }

    static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
```

`exp(0.3·Z − 0.045)` has mean 1 (`0.045 = 0.3²/2`), so average volume matches `averageVolume`.

### 7.7 `TickerState`

```java
package com.finallyapp.market.simulator;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.internal.Prices;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Mutable per-ticker state. Written only on the simulator thread; lastCompletedBar is safe to read anywhere. */
final class TickerState {

    private final TickerProfile profile;
    private final List<DailyBar> history;          // oldest → newest, completed sessions only
    private double price;                          // unrounded model state
    private BigDecimal publishedPrice;             // last rounded price
    private BigDecimal previousClose;
    private double dayOpen, dayHigh, dayLow;
    private volatile DailyBar lastCompletedBar;

    TickerState(TickerProfile profile, List<DailyBar> history) {
        this.profile = profile;
        this.history = new ArrayList<>(history);
        this.lastCompletedBar = history.getLast();
        this.previousClose = lastCompletedBar.close();
        this.price = previousClose.doubleValue();
        this.publishedPrice = previousClose;
        startDay();
    }

    TickerProfile profile() { return profile; }

    double price() { return price; }

    DailyBar lastCompletedBar() { return lastCompletedBar; }

    /** First live price is offset from the previous close by N(0, 0.5 %) so the UI shows a change at once. */
    void applyOvernightGap(RandomGenerator rng) {
        price *= Math.exp(rng.nextGaussian() * 0.005);
        publishedPrice = Prices.toTick(price);
        startDay();
    }

    /** Apply a model step; returns true if the rounded (published) price changed. */
    boolean update(double next) {
        price = next;
        dayHigh = Math.max(dayHigh, next);
        dayLow = Math.min(dayLow, next);
        BigDecimal rounded = Prices.toTick(next);
        if (rounded.equals(publishedPrice)) return false;
        publishedPrice = rounded;
        return true;
    }

    PriceQuote toQuote(Instant asOf) {
        return new PriceQuote(profile.ticker(), publishedPrice, previousClose, asOf, MarketDataMode.SIMULATED);
    }

    /** End of session {@code day}: record its bar and make the current price the new previous close. */
    void closeDay(LocalDate day, RandomGenerator rng) {
        long volume = Math.round(profile.averageVolume() * Math.exp(0.3 * rng.nextGaussian() - 0.045));
        DailyBar bar = new DailyBar(profile.ticker(), day, Prices.toTick(dayOpen), Prices.toTick(dayHigh),
                Prices.toTick(dayLow), publishedPrice, volume);
        history.add(bar);
        lastCompletedBar = bar;
        previousClose = publishedPrice;
        startDay();
    }

    private void startDay() {
        dayOpen = dayHigh = dayLow = price;
    }
}
```

### 7.8 `SimulatedMarketDataSource`

```java
package com.finallyapp.market.simulator;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.config.MarketProperties;
import com.finallyapp.market.spi.MarketDataSource;
import com.finallyapp.market.spi.PriceSink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimulatedMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketDataSource.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final SimulationParameters params;
    private final PriceModel model;
    private final TickerProfiles profiles;
    private final HistoryGenerator history;
    private final RandomGenerator rng;
    private final Clock clock;
    private final boolean autoStart;
    private final ScheduledExecutorService scheduler;
    private final Executor mutator;                       // scheduler thread, or caller thread in tests
    private final Map<String, TickerState> states = new ConcurrentSkipListMap<>();   // sorted ⇒ reproducible
    private volatile PriceSink sink;
    private volatile LocalDate currentDay;

    public SimulatedMarketDataSource(MarketProperties.Simulator config) {
        this(config, Clock.systemUTC(), true);
    }

    /** autoStart=false: no background ticks, all mutations run on the caller; tests call tick(). */
    SimulatedMarketDataSource(MarketProperties.Simulator config, Clock clock, boolean autoStart) {
        this.params = SimulationParameters.from(config);
        this.model = new PriceModel(params);
        this.profiles = new TickerProfiles(params);
        this.history = new HistoryGenerator(params);
        this.rng = SimulationParameters.newRng(params.seed());
        this.clock = clock;
        this.autoStart = autoStart;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("market-sim").daemon().factory());
        this.mutator = autoStart ? scheduler : Runnable::run;
        log.info("Simulator seed = {} (set market.simulator.seed to reproduce)", params.seed());
    }

    @Override
    public void start(Set<String> tickers, PriceSink sink) {
        this.sink = sink;
        this.currentDay = LocalDate.now(clock.withZone(NEW_YORK));
        tickers.stream().sorted().forEach(this::initTicker);  // before the first tick: happens-before via submit
        if (autoStart) {
            long ms = params.tickInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::safeTick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void addTicker(String ticker) {
        mutator.execute(() -> initTicker(ticker));
    }

    @Override
    public void removeTicker(String ticker) {
        mutator.execute(() -> states.remove(ticker));
    }

    @Override
    public Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers) {
        Map<String, DailyBar> out = new HashMap<>();
        for (String t : tickers) {
            TickerState s = states.get(t);
            // Untracked tickers: history is deterministic, so generating it here gives the same bar.
            out.put(t, s != null ? s.lastCompletedBar() : history.backfill(profiles.forTicker(t), currentDay).getLast());
        }
        return out;
    }

    @Override
    public MarketDataMode mode() {
        return MarketDataMode.SIMULATED;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** One simulation step for all tickers. Package-private for tests. */
    void tick() {
        Instant now = clock.instant();
        rollDayIfNeeded(now);
        double zMarket = rng.nextGaussian();
        double marketJump = rng.nextDouble() < params.marketJumpProbabilityPerTick()
                ? rng.nextGaussian() * params.marketJumpSigma() : 0.0;
        if (marketJump != 0.0) log.debug("SIM EVENT: market shock {}", marketJump);

        Map<Sector, Double> zSector = new EnumMap<>(Sector.class);
        for (TickerState s : states.values()) {
            double zs = zSector.computeIfAbsent(s.profile().sector(), k -> rng.nextGaussian());
            double next = model.step(s.price(), s.profile(), zMarket, zs, marketJump, rng);
            if (s.update(next)) sink.publish(s.toQuote(now));
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {                       // would otherwise cancel scheduleAtFixedRate
            log.error("Simulator tick failed", e);
        }
    }

    private void initTicker(String ticker) {
        if (states.containsKey(ticker)) return;
        TickerProfile prof = profiles.forTicker(ticker);
        TickerState s = new TickerState(prof, history.backfill(prof, currentDay));
        s.applyOvernightGap(rng);
        states.put(ticker, s);
        sink.publish(s.toQuote(clock.instant()));            // immediate first quote
    }

    /**
     * Sessions close at the ET date change, but only weekday sessions. Weekend movement is folded into
     * Monday's session, so history stays weekday-only and lastCompletedBar.close == previousClose.
     */
    private void rollDayIfNeeded(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, NEW_YORK);
        if (today.equals(currentDay)) return;
        if (HistoryGenerator.isWeekday(currentDay)) {
            LocalDate closing = currentDay;
            states.values().forEach(s -> s.closeDay(closing, rng));
            states.values().forEach(s -> sink.publish(s.toQuote(now)));   // previousClose changed
            log.info("Simulator closed session {}", closing);
        }
        currentDay = today;
    }
}
```

Threading: `start` runs on the caller before the first tick is scheduled. After that only the `market-sim` thread
touches `states` values (`addTicker`/`removeTicker` are submitted to it). `fetchEndOfDay` reads the
`volatile lastCompletedBar` from any thread.

---

## 8. Massive implementation

Package `com.finallyapp.market.massive`. Endpoints and payloads: `MASSIVE_API.md` §2.

### 8.1 DTOs (`massive/dto`, one file each)

```java
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
        LastTrade lastTrade) {}    // null unless the plan includes trades

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

### 8.2 Exceptions

```java
package com.finallyapp.market.massive;

/** Any non-2xx response from Massive. */
public class MassiveApiException extends RuntimeException {
    private final int status;

    public MassiveApiException(int status, String path, String body) {
        super("Massive " + status + " for " + path + ": " + body);
        this.status = status;
    }

    public int status() { return status; }
}

/** 401: bad or missing key. Polling stops; no fallback. */
public class MassiveAuthException extends MassiveApiException {
    public MassiveAuthException(String path, String body) { super(401, path, body); }
}

/** 403: endpoint or data recency not included in the plan (e.g. snapshots on Basic). */
public class MassivePlanException extends MassiveApiException {
    public MassivePlanException(String path, String body) { super(403, path, body); }
}

/** 429: rate limited (Basic: 5 calls/min). */
public class MassiveRateLimitException extends MassiveApiException {
    public MassiveRateLimitException(String path, String body) { super(429, path, body); }
}
```

### 8.3 `MassiveClient`

```java
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

/** Thin typed wrapper over the three Massive endpoints we use. Stateless and thread-safe. */
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
```

`/v2/aggs/ticker/{t}/prev` from `MASSIVE_API.md` §2.4 is not needed: grouped daily serves any ticker from one
cached response.

### 8.4 `SnapshotPrices` (pure mapping)

```java
package com.finallyapp.market.massive;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.internal.Prices;
import com.finallyapp.market.massive.dto.Bar;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

final class SnapshotPrices {

    private SnapshotPrices() {}

    /** lastTrade.p → min.c → day.c → prevDay.c, skipping missing/zero (snapshot resets ~3:30 AM ET). */
    static Optional<Double> currentPrice(TickerSnapshot s) {
        return Stream.of(
                        s.lastTrade() == null ? null : s.lastTrade().price(),
                        close(s.min()),
                        close(s.day()),
                        close(s.prevDay()))
                .filter(p -> p != null && p > 0)
                .findFirst();
    }

    /** updated (ns) → lastTrade.t (ns) → min.t (ms) → fallback. */
    static Instant asOf(TickerSnapshot s, Instant fallback) {
        if (s.updated() != null && s.updated() > 0) return Instant.ofEpochSecond(0, s.updated());
        if (s.lastTrade() != null && s.lastTrade().timestampNanos() != null) {
            return Instant.ofEpochSecond(0, s.lastTrade().timestampNanos());
        }
        if (s.min() != null && s.min().timestampMillis() != null && s.min().timestampMillis() > 0) {
            return Instant.ofEpochMilli(s.min().timestampMillis());
        }
        return fallback;
    }

    static Optional<PriceQuote> toQuote(TickerSnapshot s, Instant fallback) {
        Double prev = close(s.prevDay());
        return currentPrice(s).map(p -> new PriceQuote(
                s.ticker(),
                Prices.of(p),
                prev == null || prev <= 0 ? null : Prices.of(prev),
                asOf(s, fallback),
                MarketDataMode.MASSIVE_LIVE));
    }

    private static Double close(Bar b) {
        return b == null ? null : b.close();
    }
}
```

`Instant.ofEpochSecond(0, nanos)` normalises a nanosecond count of any size into seconds + nanos.

### 8.5 `Sleeper`

```java
package com.finallyapp.market.massive;

import java.time.Duration;

/** Indirection over Thread.sleep so rate-limit spacing is testable without waiting. */
@FunctionalInterface
interface Sleeper {
    void sleep(Duration d) throws InterruptedException;

    Sleeper REAL = Thread::sleep;
}
```

### 8.6 `MassiveMarketDataSource`

State machine (from `MARKET_INTERFACE.md` §6.2):

```
          start()
             │  first cycle runs immediately on "massive-poller"
             ▼
   ┌──────────────────┐   snapshot → 403 (plan has no snapshots)    ┌──────────────────┐
   │   MASSIVE_LIVE   │ ──────────────────────────────────────────▶ │   MASSIVE_EOD    │
   │ snapshot / 15 s  │                                             │ grouped / 1 h    │
   └──────────────────┘                                             └──────────────────┘
      401 anywhere → log ERROR, stop polling (cached prices stay; never fall back to the simulator)
      429 / 5xx / I/O → keep cached prices, retry after base × 2^failures (max 5 min)
```

```java
package com.finallyapp.market.massive;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.config.MarketProperties;
import com.finallyapp.market.internal.Prices;
import com.finallyapp.market.massive.dto.AggregateBar;
import com.finallyapp.market.massive.dto.TickerSnapshot;
import com.finallyapp.market.spi.MarketDataSource;
import com.finallyapp.market.spi.PriceSink;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MassiveMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(MassiveMarketDataSource.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration BASIC_CALL_SPACING = Duration.ofSeconds(13);   // 5 calls/min with margin
    private static final int MAX_DAY_LOOKBACK = 5;

    /** One trading day's grouped-daily result, indexed by ticker. */
    record DayBars(LocalDate date, Map<String, AggregateBar> bars) {}

    /** Latest completed session and the one before it, valid while "yesterday ET" == anchor. */
    record EodData(LocalDate anchor, DayBars latest, DayBars previous) {}

    private final MassiveClient client;
    private final MarketProperties.Massive props;
    private final Clock clock;
    private final Sleeper sleeper;
    private final boolean autoStart;
    private final ScheduledExecutorService scheduler;
    private final Set<String> tickers = ConcurrentHashMap.newKeySet();
    private final Object eodLock = new Object();

    private volatile PriceSink sink;
    private volatile MarketDataMode mode = MarketDataMode.MASSIVE_LIVE;
    private volatile boolean stopped;
    private int consecutiveFailures;                       // poller thread only
    private EodData eod;                                   // guarded by eodLock
    private Instant lastEodCallAt = Instant.EPOCH;         // guarded by eodLock

    public MassiveMarketDataSource(MassiveClient client, MarketProperties.Massive props) {
        this(client, props, Clock.systemUTC(), Sleeper.REAL, true);
    }

    /** autoStart=false: nothing is scheduled; tests call pollOnce()/runCycle() directly. */
    MassiveMarketDataSource(MassiveClient client, MarketProperties.Massive props,
                            Clock clock, Sleeper sleeper, boolean autoStart) {
        this.client = client;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        this.autoStart = autoStart;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("massive-poller").daemon().factory());
    }

    // ---- SPI ---------------------------------------------------------------------------------

    @Override
    public void start(Set<String> initial, PriceSink sink) {
        this.sink = sink;
        tickers.addAll(initial);
        if (autoStart) scheduler.execute(this::runCycle);   // async: app startup never waits on Massive
    }

    @Override
    public void addTicker(String ticker) {
        tickers.add(ticker);
        if (autoStart) scheduler.execute(() -> pollNewTicker(ticker));
    }

    @Override
    public void removeTicker(String ticker) {
        tickers.remove(ticker);
    }

    @Override
    public Map<String, DailyBar> fetchEndOfDay(Collection<String> wanted) {
        DayBars latest = eodData().latest();
        Map<String, DailyBar> out = new HashMap<>();
        for (String t : wanted) {
            AggregateBar b = latest.bars().get(t);
            if (b != null) out.put(t, toDailyBar(b, latest.date()));
        }
        return out;
    }

    @Override
    public MarketDataMode mode() {
        return mode;
    }

    @Override
    public void close() {
        stopped = true;
        scheduler.shutdownNow();
    }

    // ---- polling loop ------------------------------------------------------------------------

    /** One cycle plus scheduling of the next. Self-rescheduling so the delay can vary (backoff, mode). */
    void runCycle() {
        if (stopped) return;
        Duration next;
        try {
            pollOnce();
            consecutiveFailures = 0;
            next = baseInterval();
        } catch (MassiveAuthException e) {
            log.error("Massive rejected MASSIVE_API_KEY (401). Market data polling stopped; "
                    + "fix the key and restart. Cached prices are kept.", e);
            stopped = true;
            return;
        } catch (RuntimeException e) {                     // 429, 5xx, I/O, "no trading day found"
            consecutiveFailures++;
            next = backoff(baseInterval(), consecutiveFailures);
            log.warn("Massive poll failed ({} in a row), retrying in {}: {}",
                    consecutiveFailures, next, e.getMessage());
        }
        if (autoStart && !stopped) scheduler.schedule(this::runCycle, next.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Package-private for tests. */
    void pollOnce() {
        if (mode == MarketDataMode.MASSIVE_LIVE) {
            try {
                pollLive(List.copyOf(tickers));
                return;
            } catch (MassivePlanException e) {
                log.warn("Massive plan has no snapshot access (403); switching to end-of-day prices");
                mode = MarketDataMode.MASSIVE_EOD;
            }
        }
        pollEod();
    }

    boolean isStopped() {
        return stopped;
    }

    static Duration backoff(Duration base, int failures) {
        long factor = 1L << Math.min(failures, 10);
        Duration d = base.multipliedBy(factor);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private Duration baseInterval() {
        return mode == MarketDataMode.MASSIVE_LIVE ? props.pollInterval() : props.eodRefreshInterval();
    }

    /** A newly tracked ticker gets a price within seconds instead of waiting for the next cycle. */
    private void pollNewTicker(String ticker) {
        try {
            if (mode == MarketDataMode.MASSIVE_LIVE) {
                pollLive(List.of(ticker));
            } else {
                EodData cached;
                synchronized (eodLock) { cached = eod; }
                if (cached != null) publishEod(ticker, cached);   // no API call on Basic
            }
        } catch (RuntimeException e) {
            log.debug("Immediate poll for {} failed; the next cycle will retry: {}", ticker, e.getMessage());
        }
    }

    // ---- LIVE --------------------------------------------------------------------------------

    private void pollLive(List<String> batch) {
        Instant now = clock.instant();
        int size = props.maxTickersPerRequest();
        for (int from = 0; from < batch.size(); from += size) {
            List<String> chunk = batch.subList(from, Math.min(from + size, batch.size()));
            for (TickerSnapshot s : client.snapshots(chunk)) {
                if (!tickers.contains(s.ticker())) continue;
                SnapshotPrices.toQuote(s, now).ifPresent(sink::publish);
            }
        }
    }

    // ---- EOD ---------------------------------------------------------------------------------

    private void pollEod() {
        EodData data = eodData();
        for (String t : tickers) publishEod(t, data);
    }

    private void publishEod(String ticker, EodData data) {
        AggregateBar bar = data.latest().bars().get(ticker);
        if (bar == null) return;                             // unknown ticker or no trades that day
        AggregateBar prev = data.previous().bars().get(ticker);
        Instant asOf = data.latest().date().atTime(LocalTime.of(16, 0)).atZone(NEW_YORK).toInstant();
        sink.publish(new PriceQuote(ticker, Prices.of(bar.close()),
                prev == null ? null : Prices.of(prev.close()), asOf, MarketDataMode.MASSIVE_EOD));
    }

    /**
     * Cached until "yesterday ET" changes, so an hourly refresh costs zero calls on most hours.
     * A refresh walks back from yesterday to the latest day with data, then to the one before it.
     */
    private EodData eodData() {
        synchronized (eodLock) {
            LocalDate anchor = LocalDate.now(clock.withZone(NEW_YORK)).minusDays(1);
            if (eod != null && eod.anchor().equals(anchor)) return eod;
            DayBars latest = findTradingDay(anchor);
            DayBars previous = findTradingDay(latest.date().minusDays(1));
            eod = new EodData(anchor, latest, previous);
            log.info("Massive EOD data loaded: latest session {}, previous {}", latest.date(), previous.date());
            return eod;
        }
    }

    private DayBars findTradingDay(LocalDate from) {
        LocalDate day = from;
        for (int calls = 0; calls < MAX_DAY_LOOKBACK; day = day.minusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            calls++;
            LocalDate d = day;
            List<AggregateBar> bars;
            try {
                bars = spaced(() -> client.groupedDaily(d));
            } catch (MassivePlanException e) {             // Basic: day not yet available → go further back
                bars = List.of();
            }
            if (!bars.isEmpty()) {
                return new DayBars(d, bars.stream().collect(
                        Collectors.toUnmodifiableMap(AggregateBar::ticker, b -> b, (a, b) -> a)));
            }
        }
        throw new MassiveApiException(404, "grouped daily", "no trading day with data on or before " + from);
    }

    /** In EOD mode (Basic plan) keep calls ≥ 13 s apart. Caller holds eodLock. */
    private <T> T spaced(Supplier<T> call) {
        if (mode == MarketDataMode.MASSIVE_EOD) {
            Duration wait = Duration.between(clock.instant(), lastEodCallAt.plus(BASIC_CALL_SPACING));
            if (!wait.isNegative() && !wait.isZero()) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while rate limiting", e);
                }
            }
        }
        try {
            return call.get();
        } finally {
            lastEodCallAt = clock.instant();
        }
    }

    private static DailyBar toDailyBar(AggregateBar b, LocalDate date) {
        return new DailyBar(b.ticker(), date, Prices.of(b.open()), Prices.of(b.high()),
                Prices.of(b.low()), Prices.of(b.close()), Math.round(b.volume()));
    }
}
```

Call budget on the free Basic plan:

| Moment | Calls |
|---|---|
| First cycle | 1 snapshot (403) + 2 grouped (typical) up to ~6 when walking back over a long weekend, 13 s apart |
| Hourly refresh, same ET date | **0** (cached by anchor date) |
| First refresh after midnight ET | 2 (typical) |
| `track("NEW")` in EOD mode | 0 (served from the cached grouped result) |

Paid plans: 1 snapshot call per 200 tickers every 15 s, plus 2 grouped calls per day for `getEndOfDay`.

---

## 9. Web layer: REST + SSE

Package `com.finallyapp.market.web`. It depends on the facade only, so it works the same in every mode.

### 9.1 `PriceDto`

```java
package com.finallyapp.market.web;

import com.finallyapp.market.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;

/** Wire format: the quote plus derived change fields. */
public record PriceDto(String ticker, BigDecimal price, BigDecimal previousClose, BigDecimal change,
                       BigDecimal changePercent, Instant asOf, String mode) {

    static PriceDto from(PriceQuote q) {
        return new PriceDto(q.ticker(), q.price(), q.previousClose(), q.change(), q.changePercent(),
                q.asOf(), q.mode().name());
    }
}
```

### 9.2 `PriceStream` (one per SSE client, conflating)

Listeners run on the market-data thread and must not block (`MARKET_INTERFACE.md` §6.4). Each client gets a
"latest per ticker" map drained by its own virtual thread. A slow client skips intermediate prices instead of
building an unbounded queue, and sends for one client stay in order.

```java
package com.finallyapp.market.web;

import com.finallyapp.market.PriceQuote;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class PriceStream {

    private final SseEmitter emitter;
    private final Set<String> filter;                   // null = all tickers
    private final ConcurrentHashMap<String, PriceQuote> pending = new ConcurrentHashMap<>();
    private final Semaphore signal = new Semaphore(0);
    private volatile boolean closed;

    PriceStream(SseEmitter emitter, Set<String> filter) {
        this.emitter = emitter;
        this.filter = filter;
        Thread.ofVirtual().name("sse-prices").start(this::drain);
    }

    /** Called on the market-data thread: O(1), never blocks. */
    void offer(PriceQuote q) {
        if (closed || (filter != null && !filter.contains(q.ticker()))) return;
        pending.put(q.ticker(), q);
        signal.release();
    }

    void close() {
        closed = true;
        signal.release();
    }

    private void drain() {
        try {
            while (!closed) {
                signal.acquire();
                signal.drainPermits();
                for (String ticker : pending.keySet()) {
                    PriceQuote q = pending.remove(ticker);
                    if (q != null && !closed) {
                        emitter.send(SseEmitter.event().name("price").data(PriceDto.from(q)));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {   // client went away / emitter completed
            emitter.completeWithError(e);
        } finally {
            closed = true;
        }
    }
}
```

### 9.3 `PriceController`

```java
package com.finallyapp.market.web;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.MarketDataService;
import com.finallyapp.market.Subscription;
import com.finallyapp.market.Tickers;
import com.finallyapp.market.UnknownTickerException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/market")
class PriceController {

    private final MarketDataService market;

    PriceController(MarketDataService market) {
        this.market = market;
    }

    /** GET /api/market/prices?tickers=AAPL,MSFT  (omit tickers for all tracked) */
    @GetMapping("/prices")
    Map<String, PriceDto> prices(@RequestParam(required = false) List<String> tickers) {
        var quotes = tickers == null ? market.getAllPrices() : market.getPrices(tickers);
        Map<String, PriceDto> out = new LinkedHashMap<>();
        quotes.forEach((t, q) -> out.put(t, PriceDto.from(q)));
        return out;
    }

    @GetMapping("/prices/{ticker}")
    PriceDto price(@PathVariable String ticker) {
        return PriceDto.from(market.requirePrice(ticker));
    }

    @GetMapping("/eod")
    Map<String, DailyBar> endOfDay(@RequestParam List<String> tickers) {
        return market.getEndOfDay(tickers);
    }

    @GetMapping("/mode")
    Map<String, String> mode() {
        MarketDataMode m = market.mode();
        return Map.of("mode", m.name(), "label", m.label());
    }

    /** Server-sent events: an initial snapshot, then one "price" event per change. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestParam(required = false) List<String> tickers) {
        Set<String> filter = tickers == null ? null
                : tickers.stream().map(Tickers::normalize).collect(Collectors.toUnmodifiableSet());
        SseEmitter emitter = new SseEmitter(0L);                 // no timeout
        PriceStream stream = new PriceStream(emitter, filter);
        Subscription sub = market.subscribe(stream::offer);      // subscribe first, then snapshot: no gap
        market.getAllPrices().values().forEach(stream::offer);
        Runnable cleanup = () -> { sub.close(); stream.close(); };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @ExceptionHandler(UnknownTickerException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> unknown(UnknownTickerException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
```

The frontend stack is still undecided. SSE works with a plain `EventSource` in any framework. Proxies that
close idle connections may need a heartbeat comment every 15–30 s (`emitter.send(SseEmitter.event().comment("hb"))`
from a shared scheduler). Add it once the deployment target is known.

### 9.4 Trade execution example

```java
@Service
class TradeService {

    private final MarketDataService market;
    private final TradeRepository trades;

    TradeService(MarketDataService market, TradeRepository trades) {
        this.market = market;
        this.trades = trades;
    }

    @Transactional
    Trade buy(UserId user, String ticker, int quantity) {
        PriceQuote quote = market.requirePrice(ticker);                 // in-memory, no I/O
        BigDecimal cost = quote.price().multiply(BigDecimal.valueOf(quantity));
        // … check and debit cash …
        return trades.save(Trade.buy(user, quote.ticker(), quantity, quote.price(), quote.asOf(), quote.mode()));
    }
}
```

Storing `asOf` and `mode` with each trade makes it auditable ("filled at the 15-min-delayed price as of 14:31").

---

## 10. Testing

No test touches the real Massive API or sleeps. Sources run in **manual mode** (`autoStart=false`), so tests drive
them by calling `tick()` / `pollOnce()`.

### 10.1 Test helpers

```java
/** Clock whose time tests advance by hand. */
final class MutableClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    MutableClock(Instant start) { this(start, ZoneOffset.UTC); }
    private MutableClock(Instant now, ZoneId zone) { this.now = now; this.zone = zone; }

    void advance(Duration d) { now = now.plus(d); }
    void set(Instant instant) { now = instant; }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new DelegatingZoneClock(this, z); }

    /** Shares the parent's time, so advancing the parent moves zone views too. */
    private static final class DelegatingZoneClock extends Clock {
        private final MutableClock parent;
        private final ZoneId zone;
        DelegatingZoneClock(MutableClock parent, ZoneId zone) { this.parent = parent; this.zone = zone; }
        @Override public Instant instant() { return parent.instant(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return new DelegatingZoneClock(parent, z); }
    }
}

/** Records everything a source publishes. */
final class RecordingSink implements PriceSink {
    final List<PriceQuote> all = Collections.synchronizedList(new ArrayList<>());
    final Map<String, PriceQuote> latest = new ConcurrentHashMap<>();

    @Override public void publish(PriceQuote q) { all.add(q); latest.put(q.ticker(), q); }
    void clear() { all.clear(); latest.clear(); }
}

static MarketProperties.Simulator simConfig(long seed) {
    return new MarketProperties.Simulator(Duration.ofMillis(500), seed, 1.0, 0.5, 0.4,
            Duration.ofHours(2), 0.02, 0.015, 260);
}
```

### 10.2 Contract test (both sources)

```java
abstract class MarketDataSourceContractTest {

    protected RecordingSink sink = new RecordingSink();
    protected MarketDataSource source;

    /** A source in manual mode. */
    protected abstract MarketDataSource newSource();

    /** Make the source produce one round of quotes. */
    protected abstract void advance();

    @BeforeEach void setUp() { source = newSource(); }
    @AfterEach void tearDown() { source.close(); }

    @Test void publishesAQuoteForEveryStartTicker() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        assertThat(sink.latest).containsOnlyKeys("AAPL", "MSFT");
    }

    @Test void addedTickerGetsAQuote() {
        source.start(Set.of("AAPL"), sink);
        source.addTicker("NVDA");
        advance();
        assertThat(sink.latest).containsKey("NVDA");
    }

    @Test void removedTickerStopsPublishing() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        source.removeTicker("MSFT");
        sink.clear();
        for (int i = 0; i < 20; i++) advance();
        assertThat(sink.all).noneMatch(q -> q.ticker().equals("MSFT"));
    }

    @Test void quotesAreWellFormed() {
        source.start(Set.of("AAPL", "MSFT"), sink);
        advance();
        assertThat(sink.all).allSatisfy(q -> {
            assertThat(q.price()).isPositive();
            assertThat(q.price().scale()).isEqualTo(4);
            assertThat(q.ticker()).isUpperCase();
            assertThat(q.asOf()).isNotNull();
            assertThat(q.mode()).isEqualTo(source.mode());
        });
    }

    @Test void closeIsIdempotent() {
        source.start(Set.of("AAPL"), sink);
        source.close();
        assertThatCode(source::close).doesNotThrowAnyException();
    }
}
```

### 10.3 Simulator tests

```java
class SimulatedMarketDataSourceTest extends MarketDataSourceContractTest {

    // Monday 2026-10-26 15:00 UTC = 11:00 ET
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T15:00:00Z"));
    private SimulatedMarketDataSource sim;

    @Override protected MarketDataSource newSource() {
        sim = new SimulatedMarketDataSource(simConfig(42), clock, false);
        return sim;
    }

    @Override protected void advance() { sim.tick(); }

    @Test void sameSeedGivesIdenticalSequence() {
        List<PriceQuote> first = run(42), second = run(42);
        assertThat(first).isNotEmpty().isEqualTo(second);
        assertThat(run(43)).isNotEqualTo(first);
    }

    private List<PriceQuote> run(long seed) {
        var s = new SimulatedMarketDataSource(simConfig(seed), new MutableClock(clock.instant()), false);
        var rec = new RecordingSink();
        s.start(Set.of("AAPL", "MSFT", "ZZZZ"), rec);
        for (int i = 0; i < 1_000; i++) s.tick();
        s.close();
        return List.copyOf(rec.all);
    }

    @Test void startPublishesWithNonZeroChange() {
        sim.start(Set.of("AAPL"), sink);
        PriceQuote q = sink.latest.get("AAPL");
        assertThat(q.previousClose()).isEqualByComparingTo("230.0000");
        assertThat(q.price()).isNotEqualByComparingTo(q.previousClose());   // overnight gap
    }

    @Test void weekdayRolloverMovesPreviousCloseAndAppendsBar() {
        sim.start(Set.of("AAPL"), sink);
        for (int i = 0; i < 100; i++) sim.tick();
        BigDecimal lastPrice = sink.latest.get("AAPL").price();

        clock.set(Instant.parse("2026-10-27T04:30:00Z"));                   // 00:30 ET Tuesday
        sim.tick();

        DailyBar monday = sim.fetchEndOfDay(List.of("AAPL")).get("AAPL");
        assertThat(monday.date()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(monday.close()).isEqualByComparingTo(lastPrice);
        assertThat(sink.latest.get("AAPL").previousClose()).isEqualByComparingTo(lastPrice);
    }

    @Test void historyEndsOnLastWeekdayAndMatchesPreviousClose() {
        sim.start(Set.of("AAPL"), sink);
        DailyBar bar = sim.fetchEndOfDay(List.of("AAPL")).get("AAPL");
        assertThat(bar.date()).isEqualTo(LocalDate.of(2026, 10, 23));      // Friday
        assertThat(bar.close()).isEqualByComparingTo(sink.latest.get("AAPL").previousClose());
    }
}
```

```java
class PriceModelTest {

    /** Daily steps (dt = 1/252) with jumps switched off, as in MARKET_SIMULATOR.md §7. */
    private final SimulationParameters params = new SimulationParameters(
            Duration.ofMillis(500), 1.0 / 252, 0.5, 0.4, 0.0, 0.02, 0.0, 0.015, 260, 1L);
    private final TickerProfile prof = new TickerProfile("X", 100, 0.0, 0.25, Sector.TECH, 0.5, 0.4, 1_000_000);

    @Test void logReturnsHaveExpectedVolatility() {
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(7);
        int n = 100_000;
        double sum = 0, sumSq = 0, price = 100;
        for (int i = 0; i < n; i++) {
            double next = model.step(price, prof, rng.nextGaussian(), rng.nextGaussian(), 0.0, rng);
            double r = Math.log(next / price);
            sum += r; sumSq += r * r; price = next;
        }
        double std = Math.sqrt(sumSq / n - Math.pow(sum / n, 2));
        assertThat(std).isCloseTo(0.25 * Math.sqrt(params.dt()), withinPercentage(2));
    }

    @Test void pricesStayPositive() {
        var wild = new TickerProfile("W", 1, 0, 2.0, Sector.TECH, 0.5, 0.4, 1);
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(3);
        double price = 1;
        for (int i = 0; i < 1_000_000; i++) {
            price = model.step(price, wild, rng.nextGaussian(), rng.nextGaussian(), 0, rng);
            assertThat(price).isPositive();
        }
    }
}
```

Jumps are switched off in the volatility test on purpose. At the defaults (500 ms ticks, a jump every ~2 h,
jumpσ = 2 %), the jump variance per tick (`p_jump · jumpσ²` ≈ 2.8e-8) is about five times the diffusion variance
(`σ² · Δt` ≈ 5.3e-9 for σ = 0.25). With only a handful of jumps in 100k steps the estimate would be noisy. The
consequence for the product: most visible intraday movement at default settings comes from news jumps, which
matches the demo goal in `MARKET_SIMULATOR.md` R3. Lower `jump-sigma` or raise `mean-time-between-jumps` for calmer
prices.

```java
class TickerProfilesTest {
    @Test void unknownTickerIsStable() {
        var p = SimulationParameters.from(simConfig(1));
        assertThat(TickerProfiles.synthesize("ZZZZ", p)).isEqualTo(TickerProfiles.synthesize("ZZZZ", p));
        assertThat(TickerProfiles.synthesize("ZZZZ", p).initialPrice()).isBetween(10.0, 500.0);
    }
}

class HistoryGeneratorTest {
    @Test void barsAreConsistent() {
        var p = SimulationParameters.from(simConfig(1));
        var prof = new TickerProfiles(p).forTicker("AAPL");
        List<DailyBar> bars = new HistoryGenerator(p).backfill(prof, LocalDate.of(2026, 10, 26));
        assertThat(bars).hasSize(260);
        assertThat(bars.getLast().close()).isEqualByComparingTo("230.00");
        assertThat(bars).allSatisfy(b -> {
            assertThat(b.date().getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(b.low()).isLessThanOrEqualTo(b.open().min(b.close()));
            assertThat(b.high()).isGreaterThanOrEqualTo(b.open().max(b.close()));
        });
    }
}
```

### 10.4 Massive tests (`MockRestServiceServer`)

```java
class MassiveMarketDataSourceTest extends MarketDataSourceContractTest {

    private static final String BASE = "https://api.massive.com";
    // Monday 2026-10-26 11:00 ET → "yesterday" is Sunday → latest session Fri 10-23, previous Thu 10-22
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC);
    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private MassiveMarketDataSource massive;

    private static final String SNAPSHOT = """
        { "status": "OK", "tickers": [
          { "ticker": "AAPL", "updated": 1792940400000000000,
            "min": { "c": 231.40, "t": 1792940340000 }, "prevDay": { "c": 229.87 } },
          { "ticker": "MSFT", "updated": 1792940400000000000,
            "lastTrade": { "p": 421.15, "t": 1792940399000000000 },
            "min": { "c": 421.00 }, "prevDay": { "c": 418.00 } },
          { "ticker": "NVDA", "updated": 1792940400000000000,
            "day": { "c": 131.25 }, "prevDay": { "c": 129.10 } } ] }
        """;

    private MassiveMarketDataSource create() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var props = new MarketProperties.Massive("test-key", BASE, Duration.ofSeconds(15), Duration.ofHours(1), 200);
        return new MassiveMarketDataSource(new MassiveClient(builder, BASE, "test-key"), props, clock, sleeps::add, false);
    }

    @Override protected MarketDataSource newSource() {
        massive = create();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/v2/snapshot/")))
              .andExpect(header("Authorization", "Bearer test-key"))
              .andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        return massive;
    }

    @Override protected void advance() { massive.pollOnce(); }

    @Test void priceFallbackChain() {
        massive.start(Set.of("AAPL", "MSFT", "NVDA"), sink);
        massive.pollOnce();
        assertThat(sink.latest.get("MSFT").price()).isEqualByComparingTo("421.15");   // lastTrade.p
        assertThat(sink.latest.get("AAPL").price()).isEqualByComparingTo("231.40");   // min.c
        assertThat(sink.latest.get("NVDA").price()).isEqualByComparingTo("131.25");   // day.c
        assertThat(sink.latest.get("AAPL").previousClose()).isEqualByComparingTo("229.87");
        assertThat(sink.latest.get("AAPL").mode()).isEqualTo(MarketDataMode.MASSIVE_LIVE);
    }
}

class MassiveEodModeTest {

    // same fixture fields and create() as above, without the catch-all snapshot expectation

    @Test void switchesToEodOn403AndWalksBackOverWeekend() {
        var source = create();
        server.expect(requestTo(startsWith(BASE + "/v2/snapshot/")))
              .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"status\":\"NOT_AUTHORIZED\"}"));
        server.expect(requestTo(BASE + "/v2/aggs/grouped/locale/us/market/stocks/2026-10-23?adjusted=true"))
              .andRespond(withSuccess("""
                  {"status":"OK","resultsCount":1,"results":[
                    {"T":"AAPL","o":230.1,"h":232.0,"l":229.5,"c":231.62,"v":51000000,"t":1792785600000}]}
                  """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/aggs/grouped/locale/us/market/stocks/2026-10-22?adjusted=true"))
              .andRespond(withSuccess("""
                  {"status":"OK","resultsCount":1,"results":[
                    {"T":"AAPL","o":228.0,"h":230.4,"l":227.9,"c":229.87,"v":48000000,"t":1792699200000}]}
                  """, MediaType.APPLICATION_JSON));

        var sink = new RecordingSink();
        source.start(Set.of("AAPL"), sink);
        source.pollOnce();

        PriceQuote q = sink.latest.get("AAPL");
        assertThat(source.mode()).isEqualTo(MarketDataMode.MASSIVE_EOD);
        assertThat(q.price()).isEqualByComparingTo("231.62");
        assertThat(q.previousClose()).isEqualByComparingTo("229.87");
        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-10-23T20:00:00Z"));   // 16:00 EDT
        assertThat(sleeps).containsExactly(Duration.ofSeconds(13));              // second grouped call spaced
        server.verify();

        source.pollOnce();                         // same ET date: served from cache, no further requests
        server.verify();
    }

    @Test void unauthorizedStopsPolling() {
        var source = create();
        server.expect(requestTo(startsWith(BASE + "/v2/snapshot/"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        source.start(Set.of("AAPL"), new RecordingSink());
        source.runCycle();
        assertThat(source.isStopped()).isTrue();
    }

    @Test void backoffDoublesAndCaps() {
        Duration base = Duration.ofSeconds(15);
        assertThat(MassiveMarketDataSource.backoff(base, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(MassiveMarketDataSource.backoff(base, 3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(MassiveMarketDataSource.backoff(base, 9)).isEqualTo(Duration.ofMinutes(5));
    }
}
```

### 10.5 Facade, cache and wiring tests

```java
class PriceCacheTest {
    private final PriceCache cache = new PriceCache();
    private final PriceQuote q = new PriceQuote("AAPL", new BigDecimal("1.0000"), null, Instant.EPOCH,
            MarketDataMode.SIMULATED);

    @Test void throwingListenerDoesNotBlockOthers() {
        List<PriceQuote> got = new ArrayList<>();
        cache.addListener(x -> { throw new IllegalStateException("boom"); });
        cache.addListener(got::add);
        cache.publish(q);
        assertThat(got).containsExactly(q);
    }

    @Test void duplicatesAreSuppressedAndUnsubscribeWorks() {
        List<PriceQuote> got = new ArrayList<>();
        Subscription sub = cache.addListener(got::add);
        cache.publish(q);
        cache.publish(q);
        sub.close();
        cache.publish(new PriceQuote("AAPL", new BigDecimal("2.0000"), null, Instant.EPOCH, MarketDataMode.SIMULATED));
        assertThat(got).hasSize(1);
    }
}

class DefaultMarketDataServiceTest {
    @Test void untrackedTickerQuotesAreDroppedAndDefaultsStay() {
        var sim = new SimulatedMarketDataSource(simConfig(42), new MutableClock(Instant.parse("2026-10-26T15:00:00Z")), false);
        var service = new DefaultMarketDataService(sim, List.of("AAPL"));
        service.start();
        service.track("tsla");
        assertThat(service.getPrice("TSLA")).isPresent();

        service.untrack("TSLA");
        service.untrack("AAPL");                    // default: ignored
        assertThat(service.trackedTickers()).containsExactly("AAPL");
        assertThat(service.getPrice("TSLA")).isEmpty();
        assertThatThrownBy(() -> service.requirePrice("TSLA")).isInstanceOf(UnknownTickerException.class);
        assertThatThrownBy(() -> service.getPrice("not a ticker")).isInstanceOf(IllegalArgumentException.class);
        service.stop();
    }
}

class MarketDataConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketDataConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues("market.massive.base-url=http://localhost:1", "market.massive.poll-interval=1h");

    @Test void blankKeyUsesSimulator() {
        runner.withPropertyValues("market.massive.api-key=")
              .run(ctx -> assertThat(ctx.getBean(MarketDataSource.class)).isInstanceOf(SimulatedMarketDataSource.class));
    }

    @Test void keyUsesMassive() {
        runner.withPropertyValues("market.massive.api-key=abc")
              .run(ctx -> assertThat(ctx.getBean(MarketDataSource.class)).isInstanceOf(MassiveMarketDataSource.class));
    }
}
```

`base-url=http://localhost:1` makes the one asynchronous first poll fail immediately (connection refused,
logged), so the config test never reaches the internet.

### 10.6 Optional live smoke test

```java
@Tag("live")
@EnabledIfEnvironmentVariable(named = "MASSIVE_API_KEY", matches = ".+")
class MassiveLiveSmokeTest {
    @Test void fetchesSomething() {
        var client = new MassiveClient(RestClient.builder(), "https://api.massive.com", System.getenv("MASSIVE_API_KEY"));
        assertThat(client.groupedDaily(LocalDate.now(ZoneId.of("America/New_York")).minusDays(3))).isNotNull();
    }
}
```

Excluded by default (`<excludedGroups>live</excludedGroups>` in Surefire).

---

## 11. Worked examples

### 11.1 Start-up logs

```
# no key
INFO  MarketDataConfig          : MASSIVE_API_KEY not set: using simulated market data
INFO  SimulatedMarketDataSource : Simulator seed = -4719503221188430212 (set market.simulator.seed to reproduce)

# key on the free Basic plan
INFO  MarketDataConfig          : MASSIVE_API_KEY set: using Massive market data (https://api.massive.com)
WARN  MassiveMarketDataSource   : Massive plan has no snapshot access (403); switching to end-of-day prices
INFO  MassiveMarketDataSource   : Massive EOD data loaded: latest session 2026-10-23, previous 2026-10-22
```

### 11.2 REST

```bash
curl -s localhost:8080/api/market/prices?tickers=AAPL,NVDA
```

```json
{
  "AAPL": { "ticker": "AAPL", "price": 231.4500, "previousClose": 230.0000, "change": 1.4500,
            "changePercent": 0.63, "asOf": "2026-10-26T14:31:07.512Z", "mode": "SIMULATED" },
  "NVDA": { "ticker": "NVDA", "price": 129.8800, "previousClose": 130.0000, "change": -0.1200,
            "changePercent": -0.09, "asOf": "2026-10-26T14:31:07.512Z", "mode": "SIMULATED" }
}
```

```bash
curl -s localhost:8080/api/market/mode          # {"mode":"MASSIVE_EOD","label":"End of day"}
curl -s localhost:8080/api/market/prices/zzzz!   # 400 {"error":"Invalid ticker: 'zzzz!'"}
curl -s localhost:8080/api/market/eod?tickers=AAPL
```

```json
{ "AAPL": { "ticker": "AAPL", "date": "2026-10-23", "open": 230.1000, "high": 232.0000,
            "low": 229.5000, "close": 231.6200, "volume": 51000000 } }
```

### 11.3 SSE

```bash
curl -N localhost:8080/api/market/stream?tickers=AAPL,MSFT
```

```
event:price
data:{"ticker":"AAPL","price":231.4500,"previousClose":230.0000,"change":1.4500,"changePercent":0.63,"asOf":"2026-10-26T14:31:07.512Z","mode":"SIMULATED"}

event:price
data:{"ticker":"MSFT","price":421.0800,"previousClose":420.0000,"change":1.0800,"changePercent":0.26,"asOf":"2026-10-26T14:31:07.512Z","mode":"SIMULATED"}
```

Browser:

```js
const es = new EventSource('/api/market/stream?tickers=AAPL,MSFT');
es.addEventListener('price', e => {
  const q = JSON.parse(e.data);
  updateRow(q.ticker, q.price, q.changePercent);
});
```

### 11.4 Reacting to prices in the backend (e.g. limit orders)

```java
@Component
class LimitOrderWatcher implements SmartLifecycle {
    private final MarketDataService market;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Subscription sub;

    LimitOrderWatcher(MarketDataService market) { this.market = market; }

    @Override public void start() {
        sub = market.subscribe(q -> worker.execute(() -> fillCrossedOrders(q)));   // never block the feed
    }
    @Override public void stop() { sub.close(); worker.shutdown(); }
    @Override public boolean isRunning() { return sub != null; }

    private void fillCrossedOrders(PriceQuote q) { /* … */ }
}
```

### 11.5 Simulator demo settings

```dotenv
MARKET_SIMULATOR_TIME_SCALE=60     # an hour of movement per minute
MARKET_SIMULATOR_SEED=42           # same price path every run
```

---

## 12. Design decisions and refinements

Choices made here that the planning notes left open or that change them slightly:

| # | Decision | Reason |
|---|---|---|
| 1 | The facade wraps the sink and drops quotes for untracked tickers; `untrack` also clears the cache entry | A poll in flight during `untrack` would otherwise bring the ticker back |
| 2 | `track`/`untrack` before `start` only update the set; `start` hands the full set to the source | Sources may not be called before `start` |
| 3 | Massive poller reschedules itself (`schedule`, not `scheduleAtFixedRate`) | Delay changes with mode and backoff |
| 4 | EOD data is cached by "yesterday ET"; hourly refreshes cost 0 calls until the date changes | Leaves almost all of Basic's 5 calls/min free |
| 5 | Grouped-daily 403 on Basic is treated as "no data yet" and the walk-back continues | Basic returns 403 for days not yet released |
| 6 | Previous-day endpoint (`/prev`) is not used | Grouped daily already covers any ticker |
| 7 | Simulator closes sessions only on weekdays; weekend movement goes into Monday's bar | History stays weekday-only and `lastCompletedBar.close == previousClose` |
| 8 | Simulator logs its seed; `tickers.csv` profiles get `averageVolume` (≈ $5B/day ÷ price) | Reproducible bug reports; volume needed for `DailyBar` |
| 9 | History OHLC uses a Brownian bridge between open and close | Guarantees `low ≤ open, close ≤ high` |
| 10 | Simulated prices: cent ticks, but always scale 4 | Same `BigDecimal` scale everywhere |
| 11 | Simulator env vars use explicit `${…}` placeholders | Relaxed binding does not map `MARKET_SIMULATOR_TICK_INTERVAL` to `tick-interval` |
| 12 | SSE uses a per-client conflating map + virtual thread | Market thread never blocks; slow clients get the latest price, not a backlog |
| 13 | `PriceDto` adds `change` and `changePercent` | Frontend-agnostic; no client-side maths needed |
| 14 | Endpoints under `/api/market/*` | Groups prices, EOD, mode and stream together |

## 13. Implementation order

Each step compiles and is testable on its own:

1. **Public API + SPI + `Tickers` + `Prices`** (§3–4, §5.1).
2. **`PriceCache` + `PriceCacheTest`** (§5.2, §10.5).
3. **Simulator**: `Sector`, `TickerProfile`, `SimulationParameters`, `TickerProfiles` + CSV, `PriceModel`,
   `HistoryGenerator`, `TickerState`, `SimulatedMarketDataSource`, plus their tests (§7, §10.3).
4. **Facade + config**: `DefaultMarketDataService`, `MarketProperties`, `MarketDataConfig`, `application.yml`,
   `DefaultMarketDataServiceTest`, `MarketDataConfigTest` (§5.3, §6). The app now runs with simulated prices.
5. **Web layer**: `PriceDto`, `PriceStream`, `PriceController` (§9). Try it with `curl -N`.
6. **Massive**: DTOs, exceptions, `MassiveClient`, `SnapshotPrices`, `Sleeper`, `MassiveMarketDataSource`, plus
   tests (§8, §10.4). Try it with a free Basic key (EOD mode) and, if available, a Starter key (LIVE mode).
7. **Portfolio integration**: `TrackPortfolioTickers` and `track`/`untrack` calls from the watchlist and position
   services (§5.4).
