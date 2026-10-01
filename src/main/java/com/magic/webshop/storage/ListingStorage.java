package com.magic.webshop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.magic.webshop.model.Listing;

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
import java.util.stream.Collectors;

/**
 * Thread-safe, flat-file store of THIS server's own listings. Accessed from
 * both the main thread (commands) and the web server threads, hence concurrent.
 */
public class ListingStorage {

    private static final Type LIST_TYPE = new TypeToken<List<Listing>>(){}.getType();

    private final File file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();

    public ListingStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "listings.json");
        this.logger = logger;
    }

    public synchronized void load() {
        listings.clear();
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            List<Listing> loaded = gson.fromJson(r, LIST_TYPE);
            if (loaded != null) {
                for (Listing l : loaded) listings.put(l.getId(), l);
            }
        } catch (IOException e) {
            logger.warning("Could not read listings.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(new ArrayList<>(listings.values()), LIST_TYPE, w);
            }
        } catch (IOException e) {
            logger.warning("Could not write listings.json: " + e.getMessage());
        }
    }

    public void add(Listing listing) {
        listings.put(listing.getId(), listing);
        save();
    }

    public Listing remove(String id) {
        Listing removed = listings.remove(id);
        if (removed != null) save();
        return removed;
    }

    public Listing get(String id) {
        return listings.get(id);
    }

    public List<Listing> getAll() {
        return new ArrayList<>(listings.values());
    }

    public List<Listing> getByPlayer(String uuid) {
        return listings.values().stream()
                .filter(l -> l.getSellerUuid().equals(uuid))
                .collect(Collectors.toList());
    }

    public long countByPlayer(String uuid) {
        return listings.values().stream().filter(l -> l.getSellerUuid().equals(uuid)).count();
    }
}
