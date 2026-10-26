package com.finallyapp.market.web;

import com.finallyapp.market.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;

/** Wire format: the quote plus derived change fields. */
public record PriceDto(String ticker, BigDecimal price, BigDecimal previousClose, BigDecimal change,
                       BigDecimal changePercent, Instant asOf, String mode) {

    static PriceDto from(PriceQuote q) {
        return new PriceDto(q.ticker(), q.price(), q.previousClose(), q.change(), q.changePercent(),
                q.asOf(), q.mode().name());
    }
}
