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
