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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
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
        Runnable cleanup = () -> {
            sub.close();
            stream.close();
        };
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
