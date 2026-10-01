package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Fixed web passwords set in-game ({@code /webshop setpass <密码>}).
 * Stored salted + hashed (SHA-256), never in plain text. The web login accepts
 * either a one-time code (from {@code /webshop web}) or this fixed password.
 */
public class PasswordStorage {

    private static final class Entry {
        String salt;
        String hash;
    }

    private final File file;
    private final Logger logger;
    private final Function<String, UUID> nameResolver; // player name -> stable UUID (offline-aware)
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> passwords = new ConcurrentHashMap<>();

    // brute-force guard: name -> [failedCount, windowStartMillis]
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 5;
    private static final long ATTEMPT_WINDOW_MS = 60_000L;

    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    public PasswordStorage(File dataFolder, Logger logger, Function<String, UUID> nameResolver) {
        this.file = new File(dataFolder, "passwords.json");
        this.logger = logger;
        this.nameResolver = nameResolver;
        load();
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        if (!file.exists()) return;
        try (FileReader r = new FileReader(file, StandardCharsets.UTF_8)) {
            Map<String, Entry> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) passwords.putAll(loaded);
        } catch (Exception e) {
            logger.warning("MagicWebShop: could not load passwords.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(passwords, MAP_TYPE, w);
            }
        } catch (Exception e) {
            logger.warning("MagicWebShop: could not save passwords.json: " + e.getMessage());
        }
    }

    /** Set (or overwrite) a player's fixed password. */
    public synchronized void setPassword(UUID uuid, String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Entry e = new Entry();
        e.salt = HexFormat.of().formatHex(salt);
        e.hash = sha256(e.salt, password);
        passwords.put(uuid.toString(), e);
        attempts.remove(uuid.toString());
        save();
    }

    /**
     * Verify a name+password from the web. Returns the player name on success,
     * null on failure. Enforces a 5-failures-per-minute per-name limit.
     */
    public synchronized String verify(String name, String password) {
        if (name == null || password == null || name.isBlank() || password.isBlank()) return null;
        String key = name.toLowerCase();
        long now = System.currentTimeMillis();
        long[] a = attempts.get(key);
        if (a != null && a[0] >= MAX_ATTEMPTS && now - a[1] < ATTEMPT_WINDOW_MS) {
            return "__rate_limited__"; // sentinel: throttled
        }
        UUID uuid = nameResolver.apply(name);
        if (uuid == null) { fail(key, now); return null; }
        Entry e = passwords.get(uuid.toString());
        if (e == null || !safeEquals(e.hash, sha256(e.salt, password))) { fail(key, now); return null; }
        attempts.remove(key);
        return uuid.toString();
    }

    private void fail(String key, long now) {
        long[] a = attempts.get(key);
        if (a == null || now - a[1] >= ATTEMPT_WINDOW_MS) {
            attempts.put(key, new long[]{1, now});
        } else {
            a[0]++;
        }
    }

    private String sha256(String saltHex, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(HexFormat.of().parseHex(saltHex));
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
