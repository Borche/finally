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
