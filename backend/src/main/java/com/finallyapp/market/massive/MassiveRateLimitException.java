package com.finallyapp.market.massive;

/** 429: rate limited (Basic: 5 calls/min). */
public class MassiveRateLimitException extends MassiveApiException {
    public MassiveRateLimitException(String path, String body) { super(429, path, body); }
}
