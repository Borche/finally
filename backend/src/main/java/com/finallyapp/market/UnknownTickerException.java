package com.finallyapp.market;

public class UnknownTickerException extends RuntimeException {
    private final String ticker;

    public UnknownTickerException(String ticker) {
        super("No price available for ticker " + ticker);
        this.ticker = ticker;
    }

    public String ticker() { return ticker; }
}
