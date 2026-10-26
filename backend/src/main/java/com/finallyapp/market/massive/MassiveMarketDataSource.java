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
        if (batch.isEmpty()) return;
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
