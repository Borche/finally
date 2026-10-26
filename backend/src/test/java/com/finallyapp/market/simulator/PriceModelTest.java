package com.finallyapp.market.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PriceModelTest {

    /** Daily steps (dt = 1/252) with jumps switched off. */
    private final SimulationParameters params = new SimulationParameters(
            Duration.ofMillis(500), 1.0 / 252, 0.5, 0.4, 0.0, 0.02, 0.0, 0.015, 260, 1L);
    private final TickerProfile prof = new TickerProfile("X", 100, 0.0, 0.25, Sector.TECH, 0.5, 0.4, 1_000_000);

    @Test
    void logReturnsHaveExpectedVolatility() {
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(7);
        int n = 100_000;
        double sum = 0;
        double sumSq = 0;
        double price = 100;
        for (int i = 0; i < n; i++) {
            double next = model.step(price, prof, rng.nextGaussian(), rng.nextGaussian(), 0.0, rng);
            double r = Math.log(next / price);
            sum += r;
            sumSq += r * r;
            price = next;
        }
        double std = Math.sqrt(sumSq / n - Math.pow(sum / n, 2));
        assertThat(std).isCloseTo(0.25 * Math.sqrt(params.dt()), withinPercentage(2));
    }

    @Test
    void pricesStayPositive() {
        var wild = new TickerProfile("W", 1, 0, 2.0, Sector.TECH, 0.5, 0.4, 1);
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(3);
        double price = 1;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < 1_000_000; i++) {
            price = model.step(price, wild, rng.nextGaussian(), rng.nextGaussian(), 0, rng);
            min = Math.min(min, price);
        }
        assertThat(min).isGreaterThanOrEqualTo(0.01);
    }

    @Test
    void sameSectorMoreCorrelatedThanDifferentSector() {
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(11);
        var a = new TickerProfile("A", 100, 0, 0.3, Sector.TECH, 0.5, 0.4, 1);
        var b = new TickerProfile("B", 100, 0, 0.3, Sector.TECH, 0.5, 0.4, 1);
        var c = new TickerProfile("C", 100, 0, 0.3, Sector.FINANCE, 0.5, 0.4, 1);
        int n = 200_000;
        double[] ra = new double[n];
        double[] rb = new double[n];
        double[] rc = new double[n];
        for (int i = 0; i < n; i++) {
            double zm = rng.nextGaussian();
            double zTech = rng.nextGaussian();
            double zFin = rng.nextGaussian();
            ra[i] = Math.log(model.step(100, a, zm, zTech, 0, rng) / 100);
            rb[i] = Math.log(model.step(100, b, zm, zTech, 0, rng) / 100);
            rc[i] = Math.log(model.step(100, c, zm, zFin, 0, rng) / 100);
        }
        assertThat(correlation(ra, rb)).isCloseTo(0.41, org.assertj.core.data.Offset.offset(0.03));
        assertThat(correlation(ra, rc)).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void marketJumpMovesPriceByBeta() {
        var model = new PriceModel(params);
        var calm = new TickerProfile("K", 100, 0, 1e-9, Sector.TECH, 0.5, 0.0, 1);
        var rng = SimulationParameters.newRng(1);
        double next = model.step(100, calm, 0, 0, -0.10, rng);
        assertThat(next).isCloseTo(100 * Math.exp(-0.05), withinPercentage(0.01));
    }

    @Test
    void jumpsOccurWhenProbabilityIsOne() {
        var jumpy = new SimulationParameters(Duration.ofMillis(500), 1e-12, 0.5, 0.4, 1.0, 0.05, 0.0, 0.015, 10, 1L);
        var model = new PriceModel(jumpy);
        var tiny = new TickerProfile("J", 100, 0, 1e-9, Sector.TECH, 0.5, 0.4, 1);
        var rng = SimulationParameters.newRng(5);
        int moved = 0;
        for (int i = 0; i < 100; i++) {
            if (Math.abs(model.step(100, tiny, 0, 0, 0, rng) - 100) > 0.01) moved++;
        }
        assertThat(moved).isGreaterThan(80);
    }

    @Test
    void priceFloorIsOneCent() {
        var model = new PriceModel(params);
        var rng = SimulationParameters.newRng(2);
        double next = model.step(0.01, new TickerProfile("F", 1, 0, 0.5, Sector.TECH, 0.5, 0.4, 1), -50, -50, -5, rng);
        assertThat(next).isEqualTo(0.01);
    }

    private static double correlation(double[] x, double[] y) {
        int n = x.length;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}
