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

    public DefaultMarketDataService(MarketDataSource source, Collection<String> defaultTickers, Clock clock) {
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
        wanted.forEach(t -> {
            DailyBar b = day.get(t);
            if (b != null) out.put(t, b);
        });
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
