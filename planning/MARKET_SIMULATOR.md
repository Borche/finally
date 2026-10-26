# Market Simulator — Approach and Code Structure

The simulator is the `MarketDataSource` used when `MASSIVE_API_KEY` is **not** set (see `MARKET_INTERFACE.md` §5).
It produces continuously moving, realistic-looking prices for any ticker, with no network access and no
API key. It is meant for local development, demos and automated tests.

---

## 1. Requirements

| # | Requirement | Why |
|---|---|---|
| R1 | Prices move every ~500 ms and look like real stocks: random-walk noise, never negative, plausible daily ranges | The UI should feel alive; P&L should change visibly |
| R2 | Stocks move **together** to a degree (market and sector correlation) | A portfolio should not be perfectly diversified noise; "market down day" should be visible |
| R3 | Occasional **jumps** (news events) | Makes demos interesting; exercises alerts and limit orders |
| R4 | Known tickers start near realistic prices; **any** ticker works | Users can add arbitrary symbols to watchlists |
| R5 | Unknown tickers get **stable** parameters across restarts | `ZZZZ` shouldn't be $12 today and $480 after restart |
| R6 | Provides `previousClose` and end-of-day bars | Same `PriceQuote`/`DailyBar` contract as Massive |
| R7 | **Deterministic** given a seed, and testable without sleeping | Reproducible tests and bug reports |
| R8 | Cheap: hundreds of tickers on one thread with negligible CPU use | It runs inside the backend process |

## 2. Price model

### 2.1 Geometric Brownian Motion (GBM)

GBM is the standard model behind Black–Scholes. Log-returns are normally distributed, so prices stay positive
(R1). The exact discretisation for one step of length `Δt` (in years) is:

```
S(t+Δt) = S(t) · exp( (μ − σ²/2)·Δt + σ·√Δt · Z )      Z ~ N(0,1)
```

* `μ`: annual drift (e.g. 0.08 = 8 %/yr; small effect at intraday scale).
* `σ`: annual volatility (e.g. 0.25 for a large cap, 0.60 for TSLA-like names).
* `Δt`: simulated time per tick, measured in **trading time**:

```
Δt = tickInterval_seconds × timeScale / (252 days × 6.5 h × 3600 s)  ≈ 8.48e-8 for a 500 ms tick
```

At `timeScale = 1` and σ = 0.25, a tick moves the price by about 0.007 % (≈1.5¢ on a $200 stock), and an hour
moves it by about 0.6 %. That is realistic. For demos, `market.simulator.time-scale` (e.g. 60) compresses an hour
of market movement into a minute.

### 2.2 Correlation: one-factor-plus-sector model

Rather than a full correlation matrix (Cholesky of N×N, which has to be rebuilt whenever a ticker is added), each
tick draws one **market** shock, one shock **per sector**, and one **idiosyncratic** shock per ticker:

```
Z_i = β_m · Z_market + β_s · Z_sector(i) + √(1 − β_m² − β_s²) · Z_idio,i
```

`Z_i` is still N(0,1), and the implied correlations are:

| Pair | Correlation | Defaults (β_m = 0.5, β_s = 0.4) |
|---|---|---|
| same sector | β_m² + β_s² | 0.41 |
| different sectors | β_m² | 0.25 |

This is O(N) per tick, and adding a ticker is just adding a row (R2, R8). Index ETFs like `SPY` use
β_m = 0.95, β_s = 0 (sector `INDEX`), so they track the market factor.

### 2.3 Jumps (news events)

On each tick, each ticker independently has a small probability of a jump:

```
p_jump per tick = tickInterval / meanTimeBetweenJumps      (default: 1 jump per ticker per ~2 h of sim time)
jump factor     = exp(J),  J ~ N(0, jumpSigma),   jumpSigma = 0.02 (≈ ±2 %, occasionally ±5 %)
```

Market-wide shocks (all tickers, scaled by each ticker's `β_m`) happen roughly once per simulated trading day
with `jumpSigma = 0.015`. Jumps are logged at DEBUG (`"SIM EVENT: NVDA jumped +3.1%"`) to help when demoing alerts (R3).

### 2.4 Rounding and publishing

* The model state is a `double` (`logPrice` would also work). Published prices are rounded to **cents** for
  stocks ≥ $1 and to $0.0001 below $1, then converted to `BigDecimal`.
* A quote is published only if the rounded price changed. This keeps the UI and SSE traffic quiet for low-vol names.
* Price floor: `max(price, 0.01)`. GBM never goes ≤ 0, but this guards against rounding.

### 2.5 Sessions and previous close

* The simulator ignores market hours by default and moves 24/7, so developers working in the evening still see
  movement. `market.simulator.respect-market-hours=true` (future) freezes prices outside 09:30–16:00 ET.
* **Previous close:** at startup every ticker gets `previousClose = initialPrice`, and the first live price is
  offset by a small "overnight gap" (`N(0, 0.5 %)`) so the UI shows a non-zero change straight away.
* **Day rollover:** when the America/New_York date changes, each ticker's `previousClose := lastPrice` and the
  finished day is appended to its in-memory daily history (R6).

## 3. Ticker profiles

```java
public record TickerProfile(
        String ticker,
        double initialPrice,
        double annualDrift,        // μ
        double annualVolatility,   // σ
        Sector sector,
        double marketBeta,         // β_m
        double sectorBeta) {}      // β_s
```

### 3.1 Built-in profiles (R4)

Stored in `src/main/resources/simulator/tickers.csv`. Values are approximate and only need to look plausible:

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

### 3.2 Unknown tickers (R5)

Parameters derive deterministically from the ticker string, so they are stable across restarts and machines:

```java
static TickerProfile synthesize(String ticker) {
    var rng = new SplittableRandom(ticker.hashCode() * 0x9E3779B97F4A7C15L);  // String.hashCode is spec-defined
    double price = Math.exp(rng.nextDouble(Math.log(10), Math.log(500)));     // log-uniform $10–$500
    double vol   = rng.nextDouble(0.20, 0.60);
    Sector sector = Sector.STOCK_SECTORS.get(rng.nextInt(Sector.STOCK_SECTORS.size()));
    return new TickerProfile(ticker, round2(price), 0.07, vol, sector, 0.5, 0.4);
}
```

The deterministic per-ticker seed is used **only** for the profile. Price paths use the simulator's main RNG
(seeded via config or random).

## 4. End-of-day bars

`fetchEndOfDay(tickers)` must return the *last completed session* (`MARKET_INTERFACE.md` §4.3). The simulator
backfills synthetic history at startup so charts and daily P&L work immediately:

* `HistoryGenerator` walks **backwards** from `initialPrice` for N trading days (default 260 ≈ 1 year, skipping
  weekends; holidays are ignored), using the same GBM with daily `Δt = 1/252`. For each day it generates OHLC by
  simulating ~78 five-minute sub-steps and taking first/max/min/last, and draws volume from a log-normal around a
  per-ticker average.
* The most recent generated bar's `close == previousClose`, so live quotes and EOD data agree.
* History uses a per-ticker deterministic RNG (`seed ⊕ ticker.hashCode()`), so it is stable for a given seed.

## 5. Code structure

```
backend/src/main/java/com/finallyapp/market/simulator/
├── SimulatedMarketDataSource.java   MarketDataSource impl: scheduler, state map, publishing
├── PriceModel.java                  pure GBM + factor + jump math (no threads, no clock)
├── TickerState.java                 mutable per-ticker state (price, previousClose, profile, day history)
├── TickerProfile.java               immutable parameters (record)
├── TickerProfiles.java              loads tickers.csv; synthesize() for unknown tickers
├── Sector.java                      enum TECH, FINANCE, HEALTH, ENERGY, CONSUMER, INDUSTRIAL, INDEX
├── HistoryGenerator.java            backfills DailyBar history
└── SimulationParameters.java        Δt, betas, jump rates derived from MarketProperties.Simulator

backend/src/main/resources/simulator/tickers.csv
```

Responsibilities are separated so the math can be unit-tested without threads, and the threading without math:

| Class | Pure? | Tested by |
|---|---|---|
| `PriceModel` | yes (inputs: state, shocks, params) | statistical tests on many steps |
| `TickerProfiles`, `HistoryGenerator` | yes | deterministic output for a seed |
| `SimulatedMarketDataSource` | no (scheduler) | `tick()` called directly with a fixed `Clock` |

### 5.1 `PriceModel`

```java
package com.finallyapp.market.simulator;

import java.util.random.RandomGenerator;

/** GBM with market/sector factors and Poisson jumps. Stateless; all randomness comes from the caller's RNG. */
final class PriceModel {

    private final SimulationParameters p;

    PriceModel(SimulationParameters p) { this.p = p; }

    /** Next price for one ticker given this tick's shared shocks. */
    double step(double price, TickerProfile prof, double zMarket, double zSector,
                double marketJump, RandomGenerator rng) {
        double bm = prof.marketBeta(), bs = prof.sectorBeta();
        double z = bm * zMarket + bs * zSector + Math.sqrt(1 - bm * bm - bs * bs) * rng.nextGaussian();

        double sigma = prof.annualVolatility();
        double logReturn = (prof.annualDrift() - 0.5 * sigma * sigma) * p.dt()
                + sigma * Math.sqrt(p.dt()) * z
                + bm * marketJump;                                     // shared market event, 0 most ticks

        if (rng.nextDouble() < p.jumpProbabilityPerTick()) {
            logReturn += rng.nextGaussian() * p.jumpSigma();          // idiosyncratic news
        }
        return Math.max(price * Math.exp(logReturn), 0.01);
    }
}
```

### 5.2 `SimulatedMarketDataSource`

```java
package com.finallyapp.market.simulator;

public final class SimulatedMarketDataSource implements MarketDataSource {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final SimulationParameters params;
    private final PriceModel model;
    private final TickerProfiles profiles;
    private final HistoryGenerator history;
    private final RandomGenerator rng;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, TickerState> states = new ConcurrentSkipListMap<>();  // sorted → reproducible
    private volatile PriceSink sink;
    private LocalDate currentDay;                       // touched only on the scheduler thread

    public SimulatedMarketDataSource(MarketProperties.Simulator config) {
        this(config, Clock.systemUTC(), true);
    }

    /** Test constructor: autoStart=false means no scheduler ticks; tests call tick() themselves. */
    SimulatedMarketDataSource(MarketProperties.Simulator config, Clock clock, boolean autoStart) { … }

    @Override public void start(Set<String> tickers, PriceSink sink) {
        this.sink = sink;
        this.currentDay = LocalDate.now(clock.withZone(NEW_YORK));
        tickers.forEach(this::initTicker);
        if (autoStart) {
            long ms = params.tickInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::safeTick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    @Override public void addTicker(String ticker) {
        scheduler.execute(() -> initTicker(ticker));    // single writer: all mutations on the scheduler thread
    }

    @Override public void removeTicker(String ticker) {
        scheduler.execute(() -> states.remove(ticker));
    }

    @Override public Map<String, DailyBar> fetchEndOfDay(Collection<String> tickers) {
        Map<String, DailyBar> out = new HashMap<>();
        for (String t : tickers) {
            TickerState s = states.get(t);
            if (s != null) out.put(t, s.lastCompletedBar());
        }
        return out;
    }

    @Override public MarketDataMode mode() { return MarketDataMode.SIMULATED; }

    @Override public void close() { scheduler.shutdownNow(); }

    /** One simulation step for all tickers. Package-private for tests. */
    void tick() {
        rollDayIfNeeded();
        double zMarket = rng.nextGaussian();
        Map<Sector, Double> zSector = new EnumMap<>(Sector.class);
        double marketJump = rng.nextDouble() < params.marketJumpProbabilityPerTick()
                ? rng.nextGaussian() * params.marketJumpSigma() : 0.0;
        Instant now = clock.instant();

        for (TickerState s : states.values()) {
            double z = zSector.computeIfAbsent(s.profile().sector(), k -> rng.nextGaussian());
            double next = model.step(s.price(), s.profile(), zMarket, z, marketJump, rng);
            if (s.update(next)) {                        // true if the rounded price changed
                sink.publish(s.toQuote(now));
            }
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {                   // an exception would cancel scheduleAtFixedRate
            log.error("Simulator tick failed", e);
        }
    }

    private void initTicker(String ticker) {
        states.computeIfAbsent(ticker, t -> {
            TickerProfile prof = profiles.forTicker(t);
            TickerState s = new TickerState(prof, history.backfill(prof, currentDay));
            s.applyOvernightGap(rng);
            sink.publish(s.toQuote(clock.instant()));    // immediate first quote
            return s;
        });
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        if (!today.equals(currentDay)) {
            states.values().forEach(s -> s.closeDay(currentDay));   // previousClose := price; append bar
            currentDay = today;
        }
    }
}
```

Notes:

* **Single-writer threading.** Only the scheduler thread mutates `TickerState`, so `TickerState` needs no locks.
  `ConcurrentHashMap` makes `fetchEndOfDay` safe from other threads, and `lastCompletedBar()` reads an immutable
  `DailyBar` reference written once at day rollover (declare that field `volatile`).
* **Iteration order.** Within one tick, RNG draws depend on `states` iteration order, so `states` is a
  `ConcurrentSkipListMap`. It is sorted, which keeps runs reproducible, and still thread-safe.
* **Startup.** `start()` initialises the initial tickers on the caller's thread *before* the first tick is
  scheduled. Submitting to the executor creates a happens-before edge, so this does not break the single-writer rule.
* **Scheduler.** `Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("market-sim").daemon().factory())`.
* **RNG.** `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)` when `market.simulator.seed` is set,
  otherwise `.create()`. Both implement `nextGaussian()` (Java 17+).

### 5.3 `TickerState`

```java
final class TickerState {
    private final TickerProfile profile;
    private final List<DailyBar> history;          // oldest → newest, completed sessions only
    private double price;                          // unrounded model state
    private BigDecimal publishedPrice;             // last rounded price we published
    private BigDecimal previousClose;
    private double dayOpen, dayHigh, dayLow;       // for building today's bar at rollover
    private volatile DailyBar lastCompletedBar;

    /** Apply a model step; returns true if the rounded price changed. */
    boolean update(double next) { … }

    PriceQuote toQuote(Instant asOf) {
        return new PriceQuote(profile.ticker(), publishedPrice, previousClose, asOf, MarketDataMode.SIMULATED);
    }

    void closeDay(LocalDate day) { … }             // append DailyBar(day, dayOpen, dayHigh, dayLow, price, vol)
    void applyOvernightGap(RandomGenerator rng) { … }
}
```

## 6. Configuration

```yaml
market:
  simulator:
    tick-interval: 500ms
    seed:                      # e.g. 42 for reproducible runs
    time-scale: 1.0            # 60 = an hour of market movement per minute
    market-beta: 0.5
    sector-beta: 0.4
    mean-time-between-jumps: 2h
    jump-sigma: 0.02
    market-jump-sigma: 0.015
    history-days: 260
```

These extend the `MarketProperties.Simulator` record shown in `MARKET_INTERFACE.md` §5.1, which lists only
the first three. `SimulationParameters` turns them into per-tick values (`dt`, `jumpProbabilityPerTick`, …) once at startup.

## 7. Testing

| Test | Assertion |
|---|---|
| `PriceModelTest.logReturnsHaveExpectedVolatility` | 100k steps at `dt = 1/252`: std-dev of log returns ≈ σ·√dt within 2 % |
| `PriceModelTest.pricesStayPositive` | σ = 2.0, 1M steps: never ≤ 0 |
| `PriceModelTest.sameSectorMoreCorrelated` | Simulated pair correlations ≈ 0.41 (same sector) vs 0.25 (different), ±0.03 |
| `TickerProfilesTest.unknownTickerStable` | `synthesize("ZZZZ")` returns equal results across calls and JVMs |
| `HistoryGeneratorTest` | 260 bars, weekdays only, `low ≤ open,close ≤ high`, last close == previousClose |
| `SimulatedMarketDataSourceTest` | With seed 42, `autoStart=false`, and a `MutableClock`: `start` publishes one quote per ticker; `tick()` ×N gives an identical sequence across runs; advancing the clock past midnight ET rolls `previousClose`; `addTicker`/`removeTicker` take effect after the scheduler task runs |
| Contract test | `SimulatedMarketDataSourceTest extends MarketDataSourceContractTest` (`MARKET_INTERFACE.md` §8) |

Statistical tests use fixed seeds, so they are deterministic rather than flaky.

## 8. Performance

Per tick per ticker: two or three Gaussian draws, one `exp`, a comparison and possibly a `BigDecimal` allocation.
For 500 tickers at 2 Hz that is about 1,000 steps/s, which is microseconds of CPU. The real cost is downstream
(SSE fan-out), which the publish-on-change rule keeps proportional to visible price changes.
