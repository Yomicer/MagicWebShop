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
        public final long expiresAt;
        public Session(String uuid, String name, long expiresAt) {
            this.uuid = uuid; this.name = name; this.expiresAt = expiresAt;
        }
    }

    private static final class Pending {
        final String uuid; final String name; final long expiresAt; int attempts;
        Pending(String uuid, String name, long expiresAt) {
            this.uuid = uuid; this.name = name; this.expiresAt = expiresAt; this.attempts = 0;
        }
    }

    private static final long CODE_TTL_MS = 5 * 60 * 1000L;
    /** A single code self-destructs after this many wrong tries (anti brute-force). */
    private static final int CODE_MAX_ATTEMPTS = 3;
    /** Web sessions expire after 24h so a stolen token cannot be used forever. */
    private static final long SESSION_TTL_MS = 24 * 60 * 60 * 1000L;
    /** No look-alike chars (0/O, 1/l/I) so players can type them reliably. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Called in-game. Returns an 8-char alphanumeric code for the player to type on the web. */
    public String issueCode(UUID uuid, String name) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        String code = sb.toString();
        codes.put(code, new Pending(uuid.toString(), name, System.currentTimeMillis() + CODE_TTL_MS));
        return code;
    }

    /** Called from the web. Returns a session token, or null if code invalid / exhausted. */
    public String redeem(String name, String code) {
        Pending p = code == null ? null : codes.get(code);
        if (p == null) return null;
        if (System.currentTimeMillis() > p.expiresAt) { codes.remove(code); return null; }
        if (name == null || !name.equalsIgnoreCase(p.name) || p.attempts >= CODE_MAX_ATTEMPTS) {
            if (++p.attempts >= CODE_MAX_ATTEMPTS) codes.remove(code); // exhausted -> burn the code
            return null;
        }
        codes.remove(code);
        return createSession(p.uuid, p.name);
    }

    /** Mint a session for an already-verified identity (used by password login too). */
    public String createSession(String uuid, String name) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(uuid, name, System.currentTimeMillis() + SESSION_TTL_MS));
        return token;
    }

    public Session get(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expiresAt) { sessions.remove(token); return null; }
        return s;
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }
}
