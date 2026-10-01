package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.magic.webshop.model.Favorite;

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
 * Persistent per-web-user favorite sellers (favorites.json).
 * Keyed by the VIEWER's uuid; each entry is a favorited seller.
 */
public class FavoritesStorage {

    private static final Type MAP_TYPE =
            new TypeToken<Map<String, List<Favorite>>>(){}.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, List<Favorite>> favorites = new ConcurrentHashMap<>();

    public FavoritesStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "favorites.json");
        this.logger = logger;
    }

    public synchronized void load() {
        favorites.clear();
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, List<Favorite>> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) favorites.putAll(loaded);
        } catch (IOException e) {
            logger.warning("Could not read favorites.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(favorites, MAP_TYPE, w);
            }
        } catch (IOException e) {
            logger.warning("Could not write favorites.json: " + e.getMessage());
        }
    }

    /** Add a favorited seller for a viewer; returns true if newly added. */
    public synchronized boolean add(String viewerUuid, Favorite fav) {
        List<Favorite> list = favorites.computeIfAbsent(viewerUuid, k -> new ArrayList<>());
        for (Favorite f : list) {
            if (f.getUuid().equals(fav.getUuid())) return false; // already favorited
        }
        list.add(fav);
        save();
        return true;
    }

    /** Remove a favorited seller for a viewer; returns true if removed. */
    public synchronized boolean remove(String viewerUuid, String sellerUuid) {
        List<Favorite> list = favorites.get(viewerUuid);
        if (list == null) return false;
        boolean removed = list.removeIf(f -> f.getUuid().equals(sellerUuid));
        if (removed) {
            if (list.isEmpty()) favorites.remove(viewerUuid);
            save();
        }
        return removed;
    }

    public synchronized boolean contains(String viewerUuid, String sellerUuid) {
        List<Favorite> list = favorites.get(viewerUuid);
        if (list == null) return false;
        return list.stream().anyMatch(f -> f.getUuid().equals(sellerUuid));
    }

    public synchronized List<Favorite> get(String viewerUuid) {
        return new ArrayList<>(favorites.getOrDefault(viewerUuid, new ArrayList<>()));
    }
}
