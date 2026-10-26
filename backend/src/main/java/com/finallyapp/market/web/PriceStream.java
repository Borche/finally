package com.finallyapp.market.web;

import com.finallyapp.market.PriceQuote;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One per SSE client. Listeners run on the market-data thread and must not block, so each client gets a
 * "latest per ticker" map drained by its own virtual thread: a slow client skips intermediate prices instead
 * of building an unbounded queue, and sends for one client stay in order.
 */
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
