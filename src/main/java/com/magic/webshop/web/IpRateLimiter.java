package com.magic.webshop.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP brute-force guard for the web login. After MAX_FAILURES failed logins
 * from one IP within WINDOW_MS the IP is blocked (further attempts are refused
 * until the window slides). Backs up the per-code and per-name limits.
 */
public class IpRateLimiter {

    private final Map<String, long[]> fails = new ConcurrentHashMap<>(); // ip -> [count, windowStart]
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 60_000L;

    /** True if this IP is currently blocked (too many recent failures). */
    public boolean isBlocked(String ip) {
        if (ip == null || ip.isBlank()) return false; // never block unknown senders outright
        long[] a = fails.get(ip);
        return a != null && a[0] >= MAX_FAILURES && System.currentTimeMillis() - a[1] < WINDOW_MS;
    }

    /** Record a failed login from this IP. */
    public void fail(String ip) {
        if (ip == null || ip.isBlank()) return;
        long now = System.currentTimeMillis();
        long[] a = fails.get(ip);
        if (a == null || now - a[1] >= WINDOW_MS) {
            fails.put(ip, new long[]{1, now});
        } else {
            a[0]++;
        }
    }

    /** Clear failures for this IP after a successful login. */
    public void reset(String ip) {
        if (ip != null && !ip.isBlank()) fails.remove(ip);
    }
}
