package com.finallyapp.market.massive;

/** 401: bad or missing key. Polling stops; no fallback. */
public class MassiveAuthException extends MassiveApiException {
    public MassiveAuthException(String path, String body) { super(401, path, body); }
}
