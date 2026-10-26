package com.finallyapp.market.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finallyapp.market.DailyBar;
import com.finallyapp.market.MarketDataMode;
import com.finallyapp.market.MarketDataService;
import com.finallyapp.market.PriceListener;
import com.finallyapp.market.PriceQuote;
import com.finallyapp.market.Subscription;
import com.finallyapp.market.UnknownTickerException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PriceControllerTest {

    private final MarketDataService market = mock(MarketDataService.class);
    private MockMvc mvc;

    private static PriceQuote quote(String ticker, String price, String prev) {
        return new PriceQuote(ticker, new BigDecimal(price), new BigDecimal(prev),
                Instant.parse("2026-10-26T14:31:07.512Z"), MarketDataMode.SIMULATED);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PriceController(market)).build();
    }

    @Test
    void allPricesIncludeDerivedChangeFields() throws Exception {
        Map<String, PriceQuote> all = new LinkedHashMap<>();
        all.put("AAPL", quote("AAPL", "231.4500", "230.0000"));
        when(market.getAllPrices()).thenReturn(all);

        mvc.perform(get("/api/market/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.AAPL.ticker").value("AAPL"))
                .andExpect(jsonPath("$.AAPL.price").value(231.45))
                .andExpect(jsonPath("$.AAPL.previousClose").value(230.0))
                .andExpect(jsonPath("$.AAPL.change").value(1.45))
                .andExpect(jsonPath("$.AAPL.changePercent").value(0.63))
                .andExpect(jsonPath("$.AAPL.mode").value("SIMULATED"));
    }

    @Test
    void pricesFilteredByTickers() throws Exception {
        when(market.getPrices(anyCollection())).thenReturn(Map.of("NVDA", quote("NVDA", "129.8800", "130.0000")));

        mvc.perform(get("/api/market/prices").param("tickers", "AAPL,NVDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.NVDA.change").value(-0.12))
                .andExpect(jsonPath("$.AAPL").doesNotExist());
        verify(market).getPrices(List.of("AAPL", "NVDA"));
    }

    @Test
    void singlePrice() throws Exception {
        when(market.requirePrice("aapl")).thenReturn(quote("AAPL", "10.0000", "9.0000"));
        mvc.perform(get("/api/market/prices/aapl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(10.0));
    }

    @Test
    void unknownTickerIs404() throws Exception {
        when(market.requirePrice("ZZZ")).thenThrow(new UnknownTickerException("ZZZ"));
        mvc.perform(get("/api/market/prices/ZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No price available for ticker ZZZ"));
    }

    @Test
    void invalidTickerIs400() throws Exception {
        when(market.requirePrice("zzzz!")).thenThrow(new IllegalArgumentException("Invalid ticker: 'zzzz!'"));
        mvc.perform(get("/api/market/prices/{t}", "zzzz!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid ticker: 'zzzz!'"));
    }

    @Test
    void modeEndpoint() throws Exception {
        when(market.mode()).thenReturn(MarketDataMode.MASSIVE_EOD);
        mvc.perform(get("/api/market/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MASSIVE_EOD"))
                .andExpect(jsonPath("$.label").value("End of day"));
    }

    @Test
    void endOfDayEndpoint() throws Exception {
        when(market.getEndOfDay(anyCollection())).thenReturn(Map.of("AAPL", new DailyBar("AAPL",
                LocalDate.of(2026, 10, 23), new BigDecimal("230.1000"), new BigDecimal("232.0000"),
                new BigDecimal("229.5000"), new BigDecimal("231.6200"), 51_000_000L)));
        mvc.perform(get("/api/market/eod").param("tickers", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.AAPL.close").value(231.62))
                .andExpect(jsonPath("$.AAPL.volume").value(51_000_000));
    }

    @Test
    void streamStartsAsyncSubscribesAndSendsInitialSnapshot() throws Exception {
        Subscription sub = mock(Subscription.class);
        when(market.subscribe(any())).thenReturn(sub);
        when(market.getAllPrices()).thenReturn(Map.of("AAPL", quote("AAPL", "1.0000", "1.0000")));

        mvc.perform(get("/api/market/stream").param("tickers", "aapl"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<PriceListener> listener = ArgumentCaptor.forClass(PriceListener.class);
        verify(market).subscribe(listener.capture());
        verify(market).getAllPrices();
        // the registered listener is the stream's offer(): feeding it must not throw
        listener.getValue().onPrice(quote("AAPL", "1.0100", "1.0000"));
    }

    @Test
    void streamRejectsInvalidTickerFilter() throws Exception {
        mvc.perform(get("/api/market/stream").param("tickers", "bad!"))
                .andExpect(status().isBadRequest());
        verify(market, never()).subscribe(any());
    }
}
