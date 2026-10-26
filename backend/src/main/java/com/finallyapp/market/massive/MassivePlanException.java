package com.finallyapp.market.massive;

/** 403: endpoint or data recency not included in the plan (e.g. snapshots on Basic). */
public class MassivePlanException extends MassiveApiException {
    public MassivePlanException(String path, String body) { super(403, path, body); }
}
