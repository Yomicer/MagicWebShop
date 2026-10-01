package com.magic.webshop.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.model.Listing;
import com.magic.webshop.model.PlayerStats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Talks to the other servers in the network over HTTP:
 * <ul>
 *   <li>periodically pulls their catalogs ({@code GET /api/listings/local})</li>
 *   <li>completes a purchase on the seller's server ({@code POST /api/complete-sale})</li>
 * </ul>
 * All calls carry the shared {@code network-secret}.
 */
public class PeerClient {

    private final PluginConfig config;
    private final Gson gson;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** serverName -> its cached listings. */
    private final Map<String, List<Listing>> cache = new ConcurrentHashMap<>();
    /** serverName -> its cached player statistics. */
    private final Map<String, List<PlayerStats>> statsCache = new ConcurrentHashMap<>();
    /** serverName -> all configured host:port links. */
    private final Map<String, List<String>> addressesByName = new ConcurrentHashMap<>();
    /** serverName -> the link that last worked (tried first next time). */
    private final Map<String, String> workingAddress = new ConcurrentHashMap<>();

    public PeerClient(PluginConfig config, Gson gson, Logger logger) {
        this.config = config;
        this.gson = gson;
        this.logger = logger;
    }

    public void rebuildAddressMap() {
        addressesByName.clear();
        for (PluginConfig.Peer p : config.getPeers()) {
            addressesByName.put(p.name, new ArrayList<>(p.addresses));
        }
        workingAddress.keySet().retainAll(addressesByName.keySet());
        cache.keySet().retainAll(addressesByName.keySet());
        statsCache.keySet().retainAll(addressesByName.keySet());
    }

    /** Links to try for a server, most-recently-working first (link aggregation). */
    private List<String> orderedAddresses(String server) {
        List<String> addrs = new ArrayList<>(addressesByName.getOrDefault(server, List.of()));
        String best = workingAddress.get(server);
        if (best != null && addrs.remove(best)) addrs.add(0, best);
        return addrs;
    }

    /** Refreshes the cached catalog from every configured peer. Call async. */
    public void refresh() {
        for (PluginConfig.Peer peer : config.getPeers()) {
            boolean ok = false;
            for (String address : orderedAddresses(peer.name)) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + address + "/api/listings/local"))
                            .header("X-Network-Secret", config.getNetworkSecret())
                            .timeout(Duration.ofSeconds(6))
                            .GET().build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        List<Listing> list = new ArrayList<>();
                        JsonArray arr = gson.fromJson(resp.body(), JsonArray.class);
                        if (arr != null) {
                            for (JsonElement el : arr) list.add(gson.fromJson(el, Listing.class));
                        }
                        cache.put(peer.name, list);
                        workingAddress.put(peer.name, address);
                        ok = true;
                        break;
                    }
                } catch (Exception e) {
                    logger.fine("Peer " + peer.name + " link " + address + " down: " + e.getMessage());
                }
            }
            if (!ok) cache.remove(peer.name);
        }
        refreshStats();
    }

    /** Pulls each peer's player statistics into {@link #statsCache}. */
    private void refreshStats() {
        for (PluginConfig.Peer peer : config.getPeers()) {
            boolean ok = false;
            for (String address : orderedAddresses(peer.name)) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + address + "/api/stats"))
                            .header("X-Network-Secret", config.getNetworkSecret())
                            .timeout(Duration.ofSeconds(6))
                            .GET().build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        List<PlayerStats> list = new ArrayList<>();
                        JsonArray arr = gson.fromJson(resp.body(), JsonArray.class);
                        if (arr != null) {
                            for (JsonElement el : arr) list.add(gson.fromJson(el, PlayerStats.class));
                        }
                        statsCache.put(peer.name, list);
                        workingAddress.put(peer.name, address);
                        ok = true;
                        break;
                    }
                } catch (Exception e) {
                    logger.fine("Peer " + peer.name + " stats link " + address + " down: " + e.getMessage());
                }
            }
            if (!ok) statsCache.remove(peer.name);
        }
    }

    /** All cached remote player statistics across every peer. */
    public List<PlayerStats> getRemoteStats() {
        List<PlayerStats> all = new ArrayList<>();
        for (List<PlayerStats> l : statsCache.values()) all.addAll(l);
        return all;
    }

    /** All cached remote listings across every peer. */
    public List<Listing> getRemoteListings() {
        List<Listing> all = new ArrayList<>();
        for (List<Listing> l : cache.values()) all.addAll(l);
        return all;
    }

    /**
     * Asks the seller's server to finalise a sale, trying each configured link
     * until one responds. Validates + removes the listing on that server,
     * credits the seller, and returns the sold item to deliver.
     */
    public JsonObject completeSale(String sellerServer, JsonObject request) {
        List<String> addrs = orderedAddresses(sellerServer);
        if (addrs.isEmpty()) return error("Unknown seller server: " + sellerServer);
        String lastErr = "unreachable";
        for (String address : addrs) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + address + "/api/complete-sale"))
                        .header("X-Network-Secret", config.getNetworkSecret())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(8))
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    workingAddress.put(sellerServer, address);
                    return gson.fromJson(resp.body(), JsonObject.class);
                }
                lastErr = "HTTP " + resp.statusCode();
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        return error("Seller server unreachable (" + lastErr + ")");
    }

    private JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    /** Fire-and-forget broadcast of a new listing to every peer (first working link). */
    public void announceToPeers(JsonObject payload) {
        String body = gson.toJson(payload);
        for (PluginConfig.Peer peer : config.getPeers()) {
            for (String address : orderedAddresses(peer.name)) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + address + "/api/announce"))
                            .header("X-Network-Secret", config.getNetworkSecret())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 200) { workingAddress.put(peer.name, address); break; }
                } catch (Exception e) {
                    logger.fine("Announce to " + peer.name + " link " + address + " failed: " + e.getMessage());
                }
            }
        }
    }
}
