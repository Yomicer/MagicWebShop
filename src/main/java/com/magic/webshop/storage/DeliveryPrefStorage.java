package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Per-player delivery preference: where purchased items go.
 * "backpack" = direct to inventory (mailbox fallback when full/offline),
 * "mailbox"  = always into the mailbox.
 * Default comes from config (delivery.default-mode).
 */
public class DeliveryPrefStorage {

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, String> prefs = new ConcurrentHashMap<>();

    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    public DeliveryPrefStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "delivery-prefs.json");
        this.logger = logger;
        load();
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        if (!file.exists()) return;
        try (FileReader r = new FileReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> loaded = gson.fromJson(r, MAP_TYPE);
            if (loaded != null) prefs.putAll(loaded);
        } catch (Exception e) {
            logger.warning("MagicWebShop: could not load delivery-prefs.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(prefs, MAP_TYPE, w);
            }
        } catch (Exception e) {
            logger.warning("MagicWebShop: could not save delivery-prefs.json: " + e.getMessage());
        }
    }

    /** "backpack" or "mailbox", or null if the player never chose. */
    public String get(UUID uuid) {
        String v = prefs.get(uuid.toString());
        return "backpack".equals(v) || "mailbox".equals(v) ? v : null;
    }

    public void set(UUID uuid, String mode) {
        if (!"backpack".equals(mode) && !"mailbox".equals(mode)) throw new IllegalArgumentException(mode);
        prefs.put(uuid.toString(), mode);
        save();
    }
}
