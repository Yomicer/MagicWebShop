package com.magic.webshop.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.model.Favorite;
import com.magic.webshop.model.Listing;
import com.magic.webshop.model.PlayerStats;
import com.magic.webshop.service.MarketService;
import com.magic.webshop.storage.FavoritesStorage;
import com.magic.webshop.storage.ListingStorage;
import com.magic.webshop.storage.PasswordStorage;

import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Transport-agnostic HTTP routing for the web shop. Both the standalone JDK
 * server ({@link WebServer}) and the shared-port Netty handler feed requests
 * here, so all endpoint logic lives in one place.
 */
public class RequestRouter {

    /** A ready-to-send HTTP response. */
    public static final class Response {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> headers = new HashMap<>();
        public Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private final PluginConfig config;
    private final Gson gson;
    private final ListingStorage listings;
    private final MarketService market;
    private final SessionManager sessions;
    private final PasswordStorage passwords;
    private final TextureService textures;
    private final FavoritesStorage favorites;
    private final IpRateLimiter rateLimiter = new IpRateLimiter();
    private final Logger logger;

    /** Strict UUID shape for player identity inputs (anti path-traversal / junk keys). */
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** Material names are plain snake_case identifiers only. */
    private static final java.util.regex.Pattern MATERIAL_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final java.util.regex.Pattern MODEL_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-:./]+$");

    public RequestRouter(PluginConfig config, Gson gson, ListingStorage listings, MarketService market,
                         SessionManager sessions, PasswordStorage passwords, TextureService textures,
                         FavoritesStorage favorites, Logger logger) {
        this.config = config;
        this.gson = gson;
        this.listings = listings;
        this.market = market;
        this.sessions = sessions;
        this.passwords = passwords;
        this.textures = textures;
        this.favorites = favorites;
        this.logger = logger;
    }

    /**
     * @param headers keys MUST be lower-cased by the caller for case-insensitive lookup.
     * @param remoteIp the client IP (used for brute-force throttling).
     */
    public Response handle(String method, String path, Map<String, String> query,
                           Map<String, String> headers, byte[] body, String remoteIp) {
        try {
            switch (path) {
                case "/api/config": return apiConfig();
                case "/api/listings": return apiListings();
                case "/api/listings/local": return apiLocalListings(headers);
                case "/api/seller": return apiSeller(query);
                case "/api/icon": return apiIcon(query);
                case "/api/head": return apiHead(query);
                case "/api/avatar": return apiAvatar(query);
                case "/api/missing-textures": return apiMissingTextures();
                case "/api/login": return apiLogin(body, remoteIp);
                case "/api/mine": return apiMine(headers);
                case "/api/stats": return apiStats(headers);
                case "/api/favorites": return apiFavorites(headers);
                case "/api/favorite": return apiFavorite(headers, body);
                case "/api/unfavorite": return apiUnfavorite(headers, body);
                case "/api/purchase": return apiPurchase(headers, body);
                case "/api/cancel": return apiCancel(headers, body);
                case "/api/complete-sale": return apiCompleteSale(headers, body);
                case "/api/announce": return apiAnnounce(headers, body);
                default: return staticFile(path);
            }
        } catch (Exception e) {
            logger.warning("HTTP route error on " + path + ": " + e.getMessage());
            return json(500, fail("Server error"));
        }
    }

    // ----------------------------------------------------------- static UI

    private Response staticFile(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return new Response(400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
        try (InputStream in = getClass().getResourceAsStream("/web" + path)) {
            if (in == null) return new Response(404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return new Response(200, contentType(path), in.readAllBytes());
        } catch (Exception e) {
            return new Response(404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ----------------------------------------------------------- endpoints

    private Response apiConfig() {
        JsonObject o = new JsonObject();
        o.addProperty("serverName", config.getServerName());
        o.addProperty("currencySymbol", config.getCurrencySymbol());
        o.addProperty("economyEnabled", config.isEconomyEnabled());
        o.addProperty("rowsPerPage", config.getShopRowsPerPage());
        o.addProperty("defaultView", config.getShopDefaultView());
        return json(200, o);
    }

    private Response apiListings() {
        List<JsonObject> out = market.catalog().stream()
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .map(this::toPublicJson).collect(Collectors.toList());
        return json(200, out);
    }

    private Response apiLocalListings(Map<String, String> headers) {
        if (!peerAuth(headers)) return peerDenied("/api/listings/local");
        return json(200, listings.getAll());
    }

    private Response apiIcon(Map<String, String> q) {
        String material = q.getOrDefault("material", "STONE");
        if (!MATERIAL_PATTERN.matcher(material).matches()) return json(400, fail("非法物品名。"));
        Integer cmd = null;
        if (q.containsKey("cmd")) {
            try { cmd = Integer.parseInt(q.get("cmd")); } catch (NumberFormatException ignored) { }
        }
        String model = q.get("model");
        if (model != null && !MODEL_PATTERN.matcher(model).matches()) model = null;
        TextureService.Icon icon = textures.get(material, cmd, model);
        Response r = new Response(200, icon.contentType(), icon.bytes());
        r.headers.put("Cache-Control", "public, max-age=86400");
        return r;
    }

    private Response apiHead(Map<String, String> q) {
        String url = q.get("u");
        TextureService.Icon icon = url == null ? null : textures.getHeadSkin(url);
        if (icon == null) icon = textures.get("PLAYER_HEAD", null, null);
        Response r = new Response(200, icon.contentType(), icon.bytes());
        r.headers.put("Cache-Control", "public, max-age=86400");
        return r;
    }

    private Response apiLogin(byte[] body, String ip) {
        if (rateLimiter.isBlocked(ip)) {
            return json(200, fail("尝试次数过多，请 1 分钟后再试。"));
        }
        JsonObject b = parse(body);
        String name = b != null && b.has("name") ? b.get("name").getAsString() : null;
        String code = b != null && b.has("code") ? b.get("code").getAsString() : null;
        String password = b != null && b.has("password") ? b.get("password").getAsString() : null;
        String uuid;
        if (code != null && !code.isEmpty()) {
            String token = sessions.redeem(name, code);
            if (token == null) {
                rateLimiter.fail(ip);
                return json(200, fail("昵称或登录码无效（登录码 5 分钟后过期，最多试 3 次）。"));
            }
            rateLimiter.reset(ip);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("token", token);
            o.addProperty("name", name);
            return json(200, o);
        }
        if (password != null && !password.isEmpty()) {
            String res = passwords.verify(name, password);
            if ("__rate_limited__".equals(res)) return json(200, fail("尝试次数过多，请 1 分钟后再试。"));
            if (res == null) {
                rateLimiter.fail(ip);
                return json(200, fail("昵称或固定密码错误。未设置密码？请在游戏里用 /webshop setpass <密码> 设置。"));
            }
            uuid = res;
            rateLimiter.reset(ip);
        } else {
            return json(200, fail("请填写登录码，或使用固定密码登录。"));
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("token", sessions.createSession(uuid, name));
        o.addProperty("name", name);
        return json(200, o);
    }

    private Response apiMine(Map<String, String> headers) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("尚未登录。"));
        List<JsonObject> out = listings.getByPlayer(s.uuid).stream()
                .map(this::toPublicJson).collect(Collectors.toList());
        return json(200, out);
    }

    /**
     * Player shop page data: avatar, aggregated statistics across the network,
     * and this seller's current listings (from every server).
     */
    private Response apiSeller(Map<String, String> q) {
        String uuid = q.get("uuid");
        if (uuid == null || uuid.isBlank()) return json(400, fail("缺少卖家 UUID。"));
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        PlayerStats st = market.aggregateStats(uuid);
        if (st != null) {
            o.addProperty("name", st.getName());
            JsonObject stats = new JsonObject();
            stats.addProperty("listedCount", st.getListedCount());
            stats.addProperty("soldCount", st.getSoldCount());
            stats.addProperty("soldItems", st.getSoldItems());
            stats.addProperty("earned", Math.round(st.getEarned() * 100.0) / 100.0);
            stats.addProperty("barterCount", st.getBarterCount());
            stats.addProperty("firstSeen", st.getFirstSeen());
            stats.addProperty("lastSeen", st.getLastSeen());
            o.add("stats", stats);
        } else {
            String name = q.get("name");
            o.addProperty("name", (name == null || name.isBlank()) ? "未知玩家" : name);
        }
        List<JsonObject> listings = market.catalog().stream()
                .filter(l -> uuid.equals(l.getSellerUuid()))
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .map(this::toPublicJson).collect(Collectors.toList());
        o.add("listings", gson.toJsonTree(listings));
        o.addProperty("avatarUrl", "/api/avatar?v=" + textures.getGeneration()
                + "&u=" + URLEncoder.encode(uuid, StandardCharsets.UTF_8));
        return json(200, o);
    }

    /** Player avatar (face composited from their skin); falls back to a head tile. */
    private Response apiAvatar(Map<String, String> q) {
        String uuid = q.get("u");
        // uuid becomes part of a filesystem path; only accept well-formed UUIDs.
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) return json(400, fail("非法玩家标识。"));
        TextureService.Icon icon = textures.getAvatar(uuid);
        if (icon == null) icon = textures.get("PLAYER_HEAD", null, null);
        Response r = new Response(200, icon.contentType(), icon.bytes());
        r.headers.put("Cache-Control", "public, max-age=86400");
        return r;
    }

    /** Materials that could not be resolved to a texture (audit/debugging). */
    private Response apiMissingTextures() {
        return json(200, textures.getMissedMaterials());
    }

    /** Local player statistics; consumed by peer servers to build shop pages. */
    private Response apiStats(Map<String, String> headers) {
        if (!peerAuth(headers)) return peerDenied("/api/stats");
        return json(200, market.localStats());
    }

    /** The current web user's favorited sellers. */
    private Response apiFavorites(Map<String, String> headers) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("请先登录。"));
        return json(200, favorites.get(s.uuid));
    }

    /** Favorite a seller (body: {uuid, name}). */
    private Response apiFavorite(Map<String, String> headers, byte[] body) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("请先登录。"));
        JsonObject b = parse(body);
        String uuid = b.has("uuid") ? b.get("uuid").getAsString() : null;
        if (uuid == null || uuid.isBlank()) return json(400, fail("缺少卖家 UUID。"));
        String name = b.has("name") ? b.get("name").getAsString() : "玩家";
        boolean added = favorites.add(s.uuid, new Favorite(uuid, name));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("added", added);
        return json(200, o);
    }

    /** Remove a favorited seller (body: {uuid}). */
    private Response apiUnfavorite(Map<String, String> headers, byte[] body) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("请先登录。"));
        JsonObject b = parse(body);
        String uuid = b.has("uuid") ? b.get("uuid").getAsString() : null;
        if (uuid == null || uuid.isBlank()) return json(400, fail("缺少卖家 UUID。"));
        boolean removed = favorites.remove(s.uuid, uuid);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("removed", removed);
        return json(200, o);
    }

    private Response apiPurchase(Map<String, String> headers, byte[] body) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("请先登录。"));
        JsonObject b = parse(body);
        MarketService.Result r = market.purchase(s, b.get("listingId").getAsString(),
                b.get("server").getAsString(), b.has("quantity") ? b.get("quantity").getAsInt() : 0);
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("message", r.message);
        return json(200, o);
    }

    private Response apiCancel(Map<String, String> headers, byte[] body) {
        SessionManager.Session s = session(headers);
        if (s == null) return json(401, fail("请先登录。"));
        JsonObject b = parse(body);
        MarketService.Result r = market.cancel(b.get("listingId").getAsString(),
                java.util.UUID.fromString(s.uuid), false);
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("message", r.message);
        return json(200, o);
    }

    private Response apiCompleteSale(Map<String, String> headers, byte[] body) {
        if (!peerAuth(headers)) return peerDenied("/api/complete-sale");
        return json(200, market.completeSale(parse(body)));
    }

    private Response apiAnnounce(Map<String, String> headers, byte[] body) {
        if (!peerAuth(headers)) return peerDenied("/api/announce");
        JsonObject payload = parse(body);
        if (payload != null) market.receiveAnnounce(payload);
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return json(200, ok);
    }

    // ----------------------------------------------------------- helpers

    private Response json(int status, Object body) {
        return new Response(status, "application/json; charset=utf-8",
                gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject parse(byte[] body) {
        if (body == null || body.length == 0) return new JsonObject();
        JsonObject o = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        return o == null ? new JsonObject() : o;
    }

    private JsonObject fail(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    /**
     * Peer authentication: the caller must present the same network-secret AND
     * declare a server name (X-Server-Name) that is configured in OUR peers.
     * This enforces mutual configuration - one-way trading is not allowed.
     */
    private boolean peerAuth(Map<String, String> headers) {
        String secret = headers.get("x-network-secret");
        if (secret == null || !secret.equals(config.getNetworkSecret())) return false;
        String from = headers.get("x-server-name");
        if (from == null || from.isBlank()) return false;
        if (from.equalsIgnoreCase(config.getServerName())) return false; // a server may not talk to itself
        return config.hasPeer(from);
    }

    private Response peerDenied(String path) {
        logger.warning("Rejected peer call " + path
                + ": 双方未互相配置（请确认双方 network-secret 一致，且互相在 peers 里配置了对方）。");
        return json(403, fail("跨服未互相配置：请双方都在 peers 里配置对方，且 network-secret 一致。"));
    }

    private SessionManager.Session session(Map<String, String> headers) {
        return sessions.get(headers.get("x-session-token"));
    }

    private JsonObject toPublicJson(Listing l) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.getId());
        o.addProperty("server", l.getServerName());
        o.addProperty("sellerName", l.getSellerName());
        o.addProperty("sellerUuid", l.getSellerUuid());
        o.addProperty("type", l.getType() == null ? "MONEY" : l.getType().name());
        o.addProperty("material", l.getMaterial());
        // Show Chinese name for vanilla items that carry no custom display name.
        String dn = l.getDisplayName();
        if (dn == null || dn.equals(com.magic.webshop.util.ItemSerializer.readableName(l.getMaterial()))) {
            dn = com.magic.webshop.util.Translations.displayName(l.getMaterial());
        }
        o.addProperty("displayName", dn);
        o.addProperty("amount", l.getAmount());
        o.addProperty("enchanted", l.isEnchanted());
        o.addProperty("price", market.currentFlat(l));
        o.addProperty("unitPrice", l.getUnitPrice());
        o.addProperty("wantedMaterial", l.getWantedMaterial());
        o.addProperty("wantedAmount", l.getWantedAmount());
        if (l.getWantedMaterial() != null) {
            String wn = (l.getWantedName() != null && !l.getWantedName().isEmpty())
                    ? l.getWantedName()
                    : com.magic.webshop.util.Translations.displayName(l.getWantedMaterial());
            o.addProperty("wantedName", wn);
        }
        o.addProperty("createdAt", l.getCreatedAt());
        o.addProperty("iconUrl", iconUrl(l));
        boolean isHead = l.getMaterial() != null && l.getMaterial().endsWith("PLAYER_HEAD")
                && l.getHeadTexture() != null;
        o.addProperty("isHead", isHead);
        if (isHead) {
            o.addProperty("headUrl", "/api/head?v=" + textures.getGeneration() + "&u="
                    + URLEncoder.encode(l.getHeadTexture(), StandardCharsets.UTF_8));
        }
        o.add("lore", gson.toJsonTree(l.getLore()));
        o.add("enchantSummary", gson.toJsonTree(l.getEnchantSummary()));
        return o;
    }

    private String iconUrl(Listing l) {
        String material = l.getMaterial();
        String template = config.getTextureCdnTemplate();
        if (template != null && !template.isBlank()) {
            return template.replace("{material}", material == null ? "" : material.toLowerCase());
        }
        StringBuilder url = new StringBuilder("/api/icon?material=")
                .append(material == null ? "STONE" : material);
        if (l.getCustomModelData() != null) url.append("&cmd=").append(l.getCustomModelData());
        if (l.getItemModel() != null && !l.getItemModel().isBlank()) {
            url.append("&model=").append(URLEncoder.encode(l.getItemModel(), StandardCharsets.UTF_8));
        }
        url.append("&v=").append(textures.getGeneration());
        return url.toString();
    }

    /** Parse a raw query string (the part after '?') into a decoded map. */
    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
