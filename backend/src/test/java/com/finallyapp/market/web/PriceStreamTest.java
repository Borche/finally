package com.finallyapp.market.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.PriceQuote;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class PriceStreamTest {

    /** Emitter that records what would be sent instead of writing to a client. */
    private static final class RecordingEmitter extends SseEmitter {
        final List<PriceDto> sent = new CopyOnWriteArrayList<>();
        volatile Throwable error;
        volatile boolean failSends;

        RecordingEmitter() { super(0L); }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSends) throw new IOException("client gone");
            for (var part : builder.build()) {
                if (part.getData() instanceof PriceDto dto) sent.add(dto);
            }
        }

        @Override
        public void completeWithError(Throwable ex) {
            error = ex;
        }
    }

    private static PriceQuote quote(String ticker, String price) {
        return new PriceQuote(ticker, new BigDecimal(price), new BigDecimal("1.0000"), Instant.EPOCH,
                MarketDataMode.SIMULATED);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("condition within 5s").isTrue();
    }

    @Test
    void sendsOfferedQuotesAsDtos() throws Exception {
        var emitter = new RecordingEmitter();
        var stream = new PriceStream(emitter, null);
        stream.offer(quote("AAPL", "2.5000"));
        await(() -> emitter.sent.size() == 1);
        PriceDto dto = emitter.sent.getFirst();
        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.change()).isEqualByComparingTo("1.5");
        assertThat(dto.mode()).isEqualTo("SIMULATED");
        stream.close();
    }

    @Test
    void filterDropsOtherTickers() throws Exception {
        var emitter = new RecordingEmitter();
        var stream = new PriceStream(emitter, Set.of("MSFT"));
        stream.offer(quote("AAPL", "2.0000"));
        stream.offer(quote("MSFT", "3.0000"));
        await(() -> emitter.sent.size() == 1);
        Thread.sleep(50);
        assertThat(emitter.sent).extracting(PriceDto::ticker).containsExactly("MSFT");
        stream.close();
    }

    @Test
    void closedStreamIgnoresOffers() throws Exception {
        var emitter = new RecordingEmitter();
        var stream = new PriceStream(emitter, null);
        stream.close();
        Thread.sleep(50);
        stream.offer(quote("AAPL", "2.0000"));
        Thread.sleep(100);
        assertThat(emitter.sent).isEmpty();
    }

    @Test
    void sendFailureCompletesEmitterWithError() throws Exception {
        var emitter = new RecordingEmitter();
        emitter.failSends = true;
        var stream = new PriceStream(emitter, null);
        stream.offer(quote("AAPL", "2.0000"));
        await(() -> emitter.error != null);
        assertThat(emitter.error).isInstanceOf(IOException.class);
        stream.close();
    }

    @Test
    void latestQuotePerTickerWins() throws Exception {
        var emitter = new RecordingEmitter();
        var stream = new PriceStream(emitter, null);
        for (int i = 1; i <= 50; i++) stream.offer(quote("AAPL", i + ".0000"));
        await(() -> !emitter.sent.isEmpty() && emitter.sent.getLast().price().compareTo(new BigDecimal("50")) == 0);
        assertThat(emitter.sent.size()).isLessThanOrEqualTo(50);
        stream.close();
    }
}
