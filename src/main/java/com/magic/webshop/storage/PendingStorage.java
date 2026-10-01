package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

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
 * Queue of items owed to players (purchases, barter proceeds) that could not be
 * delivered immediately because the recipient was offline. Delivered on join.
 * Values are Base64-encoded ItemStacks (see {@code ItemSerializer}).
 */
public class PendingStorage {

    private static final Type MAP_TYPE =
            new TypeToken<Map<String, List<String>>>(){}.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, List<String>> pending = new ConcurrentHashMap<>();

    public PendingStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pending.json");
        this.logger = logger;
    }

    public synchronized void load() {
        pending.clear();
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, List<String>> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) pending.putAll(loaded);
        } catch (IOException e) {
            logger.warning("Could not read pending.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(pending, MAP_TYPE, w);
            }
        } catch (IOException e) {
            logger.warning("Could not write pending.json: " + e.getMessage());
        }
    }

    public synchronized void queue(String uuid, String itemBase64) {
        pending.computeIfAbsent(uuid, k -> new ArrayList<>()).add(itemBase64);
        save();
    }

    public synchronized List<String> drain(String uuid) {
        List<String> items = pending.remove(uuid);
        if (items != null) save();
        return items == null ? new ArrayList<>() : items;
    }

    public synchronized boolean hasPending(String uuid) {
        List<String> l = pending.get(uuid);
        return l != null && !l.isEmpty();
    }
}
