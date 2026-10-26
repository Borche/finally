package com.finallyapp.market.massive;

/** Any non-2xx response from Massive. */
public class MassiveApiException extends RuntimeException {
    private final int status;

    public MassiveApiException(int status, String path, String body) {
        super("Massive " + status + " for " + path + ": " + body);
        this.status = status;
    }

    public int status() { return status; }
}
