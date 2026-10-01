package com.magic.webshop.web;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links a web browser to a Minecraft account.
 *
 * <p>Flow: in game the player runs {@code /webshop web} which mints a short
 * one-time code bound to their UUID. On the website they enter their name +
 * code to obtain a session token (stored in the browser). All buy/sell actions
 * from the web require a valid token, so nobody can act as another player.
 */
public class SessionManager {

    public static final class Session {
        public final String uuid;
        public final String name;
        public Session(String uuid, String name) { this.uuid = uuid; this.name = name; }
    }

    private static final class Pending {
        final String uuid; final String name; final long expiresAt;
        Pending(String uuid, String name, long expiresAt) {
            this.uuid = uuid; this.name = name; this.expiresAt = expiresAt;
        }
    }

    private static final long CODE_TTL_MS = 5 * 60 * 1000L;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Called in-game. Returns a 6-digit code for the player to type on the web. */
    public String issueCode(UUID uuid, String name) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        codes.put(code, new Pending(uuid.toString(), name, System.currentTimeMillis() + CODE_TTL_MS));
        return code;
    }

    /** Called from the web. Returns a session token, or null if code invalid. */
    public String redeem(String name, String code) {
        Pending p = codes.get(code);
        if (p == null) return null;
        if (System.currentTimeMillis() > p.expiresAt) { codes.remove(code); return null; }
        if (name == null || !name.equalsIgnoreCase(p.name)) return null;
        codes.remove(code);
        return createSession(p.uuid, p.name);
    }

    /** Mint a session for an already-verified identity (used by password login too). */
    public String createSession(String uuid, String name) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(uuid, name));
        return token;
    }

    public Session get(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }
}
