package com.magic.webshop.mail;

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
 * Persistent per-player mailbox. Values are Base64-encoded ItemStacks. Items
 * land here (e.g. from cancelling a listing) and stay until the player claims
 * them by opening their mailbox in-game.
 */
public class MailboxStorage {

    private static final Type MAP_TYPE =
            new TypeToken<Map<String, List<String>>>(){}.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, List<String>> box = new ConcurrentHashMap<>();

    public MailboxStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "mail.json");
        this.logger = logger;
    }

    public synchronized void load() {
        box.clear();
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, List<String>> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) box.putAll(loaded);
        } catch (IOException e) {
            logger.warning("Could not read mail.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(box, MAP_TYPE, w);
            }
        } catch (IOException e) {
            logger.warning("Could not write mail.json: " + e.getMessage());
        }
    }

    public synchronized void add(String uuid, String itemBase64) {
        box.computeIfAbsent(uuid, k -> new ArrayList<>()).add(itemBase64);
        save();
    }

    public synchronized List<String> get(String uuid) {
        return new ArrayList<>(box.getOrDefault(uuid, new ArrayList<>()));
    }

    public synchronized void set(String uuid, List<String> items) {
        if (items == null || items.isEmpty()) box.remove(uuid);
        else box.put(uuid, new ArrayList<>(items));
        save();
    }

    public synchronized int count(String uuid) {
        List<String> l = box.get(uuid);
        return l == null ? 0 : l.size();
    }
}
