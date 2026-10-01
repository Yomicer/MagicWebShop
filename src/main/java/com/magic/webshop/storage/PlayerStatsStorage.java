package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.magic.webshop.model.PlayerStats;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Persistent per-player marketplace statistics (stats.json). Thread-safe, flat
 * file store keyed by player uuid. Written from both the main thread (commands)
 * and the web server threads (sales), hence concurrent.
 */
public class PlayerStatsStorage {

    private static final Type MAP_TYPE =
            new TypeToken<Map<String, PlayerStats>>(){}.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, PlayerStats> stats = new ConcurrentHashMap<>();

    public PlayerStatsStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "stats.json");
        this.logger = logger;
    }

    public synchronized void load() {
        stats.clear();
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, PlayerStats> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) stats.putAll(loaded);
        } catch (IOException e) {
            logger.warning("Could not read stats.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(stats, MAP_TYPE, w);
            }
        } catch (IOException e) {
            logger.warning("Could not write stats.json: " + e.getMessage());
        }
    }

    /** Record a new listing being created by the player. */
    public synchronized void recordListing(String uuid, String name) {
        if (skip(uuid)) return;
        PlayerStats s = getOrCreate(uuid, name);
        s.setListedCount(s.getListedCount() + 1);
        touch(s);
        save();
    }

    /** Record a completed money sale by the seller. */
    public synchronized void recordSale(String uuid, String name, int items, double earned) {
        if (skip(uuid)) return;
        PlayerStats s = getOrCreate(uuid, name);
        s.setSoldCount(s.getSoldCount() + 1);
        s.setSoldItems(s.getSoldItems() + items);
        s.setEarned(s.getEarned() + earned);
        touch(s);
        save();
    }

    /** Record a completed barter trade by the seller. */
    public synchronized void recordBarter(String uuid, String name) {
        if (skip(uuid)) return;
        PlayerStats s = getOrCreate(uuid, name);
        s.setBarterCount(s.getBarterCount() + 1);
        touch(s);
        save();
    }

    /** System-shop listings (nil uuid) don't participate in player statistics. */
    private static boolean skip(String uuid) {
        return uuid == null || uuid.isBlank() || uuid.startsWith("00000000-0000-0000-0000-");
    }

    private PlayerStats getOrCreate(String uuid, String name) {
        PlayerStats s = stats.get(uuid);
        if (s == null) {
            s = new PlayerStats();
            s.setUuid(uuid);
            s.setName(name);
            s.setFirstSeen(System.currentTimeMillis());
            stats.put(uuid, s);
        } else if (name != null && !name.isBlank()) {
            s.setName(name);
        }
        return s;
    }

    private void touch(PlayerStats s) {
        s.setLastSeen(System.currentTimeMillis());
    }

    public PlayerStats get(String uuid) {
        return stats.get(uuid);
    }

    public List<PlayerStats> getAll() {
        return new ArrayList<>(stats.values());
    }
}
