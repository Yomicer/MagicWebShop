package com.magic.webshop.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magic.webshop.util.IconRenderer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Resolves icons for the web UI, in priority order:
 *   1. the server resource pack (if configured), incl. CustomModelData items
 *   2. the vanilla texture mirror (downloaded once, then cached)
 *   3. a clean generated SVG tile (always works, even offline)
 * Also serves player/custom head skins (for PLAYER_HEAD items).
 */
public class TextureService {

    public record Icon(byte[] bytes, String contentType) {}

    private static final String SKIN_HOST = "textures.minecraft.net";

    private final File cacheDir;
    private final File headDir;
    private final File avatarDir;
    private final File localAvatarDir;
    private volatile String cdnBase;
    private volatile String version;
    private volatile ResourcePackResolver pack;
    private final Logger logger;
    private final Gson gson = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();

    private final ConcurrentHashMap<String, byte[]> memory = new ConcurrentHashMap<>();
    /** Cached player avatars (keyed by uuid) to avoid re-fetching Mojang profiles. */
    private final ConcurrentHashMap<String, byte[]> avatarMemory = new ConcurrentHashMap<>();
    /** uuids for which we already logged the "fell back to generated avatar" warning. */
    private final Set<String> avatarWarned = ConcurrentHashMap.newKeySet();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();
    /** Bumped on every reload so the web UI's icon URLs change and browsers refetch. */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(
                    (int) (System.currentTimeMillis() / 1000L)); // time-based: busts browser cache across restarts

    public int getGeneration() {
        return generation.get();
    }

    /** Materials that had no texture and fell back to a generated tile (audit). */
    public java.util.Set<String> getMissedMaterials() {
        return misses;
    }

    public TextureService(File dataFolder, String cdnBase, String version,
                          ResourcePackResolver pack, Logger logger) {
        this.cacheDir = new File(dataFolder, "textures");
        this.headDir = new File(dataFolder, "textures/heads");
        this.avatarDir = new File(dataFolder, "textures/avatars");
        this.localAvatarDir = new File(dataFolder, "avatars");
        this.cdnBase = cdnBase.endsWith("/") ? cdnBase.substring(0, cdnBase.length() - 1) : cdnBase;
        this.version = version;
        this.pack = pack;
        this.logger = logger;
        headDir.mkdirs();
        avatarDir.mkdirs();
        localAvatarDir.mkdirs();
    }

    /**
     * Re-apply config at runtime (used by /webshop reload). Swaps the resource
     * pack + mirror settings and clears caches so newly added pack textures are
     * picked up immediately.
     */
    public void reload(ResourcePackResolver newPack, String newCdnBase, String newVersion) {
        this.pack = newPack;
        this.cdnBase = newCdnBase.endsWith("/") ? newCdnBase.substring(0, newCdnBase.length() - 1) : newCdnBase;
        this.version = newVersion;
        memory.clear();
        misses.clear();
        clearTextureCache();
        generation.incrementAndGet();
    }

    /** Deletes cached item/block PNGs (keeps head avatars) so they re-resolve. */
    private void clearTextureCache() {
        File[] files = cacheDir.listFiles((d, n) -> n.endsWith(".png"));
        if (files != null) {
            for (File f : files) { try { f.delete(); } catch (Exception ignored) { } }
        }
    }

    public Icon get(String material, Integer cmd, String itemModel) {
        String name = normalize(material);
        String key = name;
        if (itemModel != null && !itemModel.isBlank()) key = "model_" + itemModel.replace(':', '_').replace('/', '_');
        else if (cmd != null) key = name + "__cmd" + cmd;

        byte[] mem = memory.get(key);
        if (mem != null) return new Icon(mem, "image/png");

        File cached = new File(cacheDir, key + ".png");
        if (cached.isFile()) {
            byte[] b = read(cached);
            if (b != null) { memory.put(key, b); return new Icon(b, "image/png"); }
        }
        if (misses.contains(key)) {
            return new Icon(IconRenderer.renderSvg(material), "image/svg+xml");
        }

        // Items like buttons / pressure plates / slabs / stairs render an overlay
        // model on top of their BASE block texture, so their real icon is the
        // base block's (e.g. STONE_BUTTON -> stone, OAK_SLAB -> oak_planks).
        // Generated overrides fix the many remaining naming mismatches.
        String textureName = baseTextureName(name);
        // keys are lowercase, so look up with the normalized name
        String override = TextureOverrides.MAP.get(name);
        // 1) resource pack (item_model / CustomModelData / vanilla name) —
        //    use the ORIGINAL material so custom models keep resolving.
        byte[] png = pack != null ? pack.resolve(material, cmd, itemModel) : null;
        // 2) generated override: pack, then bundled, then mirror
        if (png == null && override != null) {
            String[] parts = override.split("/");
            if (parts.length == 2) {
                png = pack != null ? pack.resolve(parts[1], null, null) : null;
            }
        }
        if (png == null && override != null) png = readBundled(override + ".png");
        if (png == null && override != null) png = tryFetch(override + ".png");
        // 3) base block mapping (buttons/pressure plates/slabs/stairs/wood)
        if (png == null && !textureName.equals(name)) {
            png = pack != null ? pack.resolve(textureName, null, null) : null;
        }
        // 4) vanilla textures bundled inside the plugin jar (works offline)
        if (png == null) png = readBundled("block/" + textureName + ".png");
        if (png == null) png = readBundled("item/" + textureName + ".png");
        // 5) vanilla mirror (when the server has internet)
        if (png == null) png = tryFetch("item/" + textureName + ".png");
        if (png == null) png = tryFetch("block/" + textureName + ".png");
        if (png == null) png = tryFetch(textureName + ".png");

        if (png != null) {
            png = firstFrame(png); // animated textures are tall strips -> keep one frame
            memory.put(key, png);
            write(cached, png);
            return new Icon(png, "image/png");
        }
        misses.add(key);
        return new Icon(IconRenderer.renderSvg(material), "image/svg+xml");
    }

    /** Read a vanilla texture bundled inside the plugin jar (classpath). */
    private byte[] readBundled(String path) {
        try (InputStream in = getClass().getResourceAsStream("/textures/" + path)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.Set<String> WOOD_PLANKS = java.util.Set.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "bamboo", "crimson", "warped");

    /**
     * Maps overlay-model items to the block texture they are really made of,
     * so buttons / pressure plates / slabs / stairs / wood-blocks get a real
     * icon instead of a generated tile (these blocks have no dedicated texture
     * file of their own).
     */
    private String baseTextureName(String name) {
        if (name == null) return "stone";
        String n = name.toLowerCase().replace("minecraft:", "").trim();
        if (n.endsWith("_button")) return baseOf(n.substring(0, n.length() - 7));
        if (n.endsWith("_pressure_plate") && !n.contains("weighted"))
            return baseOf(n.substring(0, n.length() - 15));
        if (n.endsWith("_slab")) return baseOf(n.substring(0, n.length() - 5));
        if (n.endsWith("_stairs")) return baseOf(n.substring(0, n.length() - 7));
        // wood blocks render the LOG side texture (jungle_wood -> jungle_log)
        if (n.endsWith("_wood")) return n.substring(0, n.length() - 5) + "_log";
        // nether wood blocks render the STEM side texture (crimson_hyphae -> crimson_stem)
        if (n.endsWith("_hyphae")) return n.substring(0, n.length() - 7) + "_stem";
        // 1.21 trial chamber blocks have no plain texture, only side/top variants
        if ("trial_spawner".equals(n)) return "trial_spawner_top_active";
        if ("vault".equals(n)) return "vault_front_active";
        return n;
    }

    private String baseOf(String root) {
        if (WOOD_PLANKS.contains(root)) return root + "_planks";
        if (root.endsWith("_planks")) return root;
        return root;
    }

    /**
     * Animated textures ship as a vertical strip of square frames (height is a
     * multiple of width). The web only needs one icon, so crop the first frame.
     */
    private byte[] firstFrame(byte[] pngBytes) {
        try {
            java.awt.image.BufferedImage img =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
            if (img == null) return pngBytes;
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= w || h % w != 0) return pngBytes; // not a vertical strip
            java.awt.image.BufferedImage frame = img.getSubimage(0, 0, w, w);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(frame, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return pngBytes;
        }
    }

    /** Fetch + cache a head skin PNG. Only allows Mojang's texture host. */
    public Icon getHeadSkin(String skinUrl) {
        if (skinUrl == null) return null;
        URI uri;
        try { uri = URI.create(skinUrl); } catch (Exception e) { return null; }
        if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(SKIN_HOST)) {
            return null; // SSRF guard: never proxy arbitrary hosts
        }
        String hash = skinUrl.substring(skinUrl.lastIndexOf('/') + 1);
        if (hash.isEmpty() || hash.length() > 128) return null;

        byte[] mem = memory.get("head_" + hash);
        if (mem != null) return new Icon(mem, "image/png");
        File cached = new File(headDir, hash + ".png");
        if (cached.isFile()) {
            byte[] b = read(cached);
            if (b != null) { memory.put("head_" + hash, b); return new Icon(b, "image/png"); }
        }
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(uri)
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body().length > 0) {
                byte[] face = compositeFace(resp.body());
                if (face == null) return null;
                memory.put("head_" + hash, face);
                write(cached, face);
                return new Icon(face, "image/png");
            }
        } catch (Exception e) {
            logger.fine("Head skin fetch failed: " + e.getMessage());
        }
        return null;
    }

    /** Crops the 8x8 face + hat overlay from a skin and upscales to a crisp 64x64 PNG. */
    private byte[] compositeFace(byte[] skinBytes) {
        try {
            java.awt.image.BufferedImage skin =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(skinBytes));
            if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16) return null;

            java.awt.image.BufferedImage face = new java.awt.image.BufferedImage(
                    8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = face.createGraphics();
            g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);   // base face
            g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);  // hat overlay
            g.dispose();

            int scale = 8; // 8x8 -> 64x64
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    8 * scale, 8 * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D go = out.createGraphics();
            go.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            go.drawImage(face, 0, 0, 8 * scale, 8 * scale, null);
            go.dispose();

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            logger.fine("Head compositing failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches + caches a player's avatar. Resolution order:
     *   0. a local avatar file the admin placed in plugins/MagicWebShop/avatars/
     *      (works with no internet at all),
     *   1. the player's head/profile on THIS server (Bukkit PlayerProfile),
     *   2. Mojang sessionserver by UUID (online-mode servers),
     *   3. Mojang sessionserver by the player NAME (offline-mode servers),
     *   4. third-party avatar services (by UUID, then by name),
     *   5. a generated Minecraft-style pixel head (never null, always looks fine).
     */
    public Icon getAvatar(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        String name = playerName(uuid);
        byte[] mem = avatarMemory.get(uuid);
        if (mem != null) return new Icon(mem, "image/png");
        File cached = new File(avatarDir, uuid + ".png");
        if (cached.isFile()) {
            byte[] b = read(cached);
            if (b != null) { avatarMemory.put(uuid, b); return new Icon(b, "image/png"); }
        }

        Icon icon = null;
        // 0) locally provided avatar (no internet needed)
        icon = localAvatar(uuid, name);
        // 1) the player's own head on this server (works on offline-mode servers)
        if (icon == null) {
            String skinUrl = fetchSkinUrlFromProfile(uuid);
            if (skinUrl != null) icon = getHeadSkin(skinUrl);
        }
        // 2) Mojang sessionserver by (online-mode) uuid
        if (icon == null) {
            String url = fetchSkinUrl(uuid);
            if (url != null) icon = getHeadSkin(url);
        }
        // 3) Mojang sessionserver by player name (offline-mode servers)
        if (icon == null && name != null) {
            String url = fetchSkinUrlByName(name);
            if (url != null) icon = getHeadSkin(url);
        }
        // 4) third-party avatar services by uuid, then by name
        if (icon == null) icon = fetchThirdPartyAvatar(uuid, name);
        // 5) generated Minecraft-style pixel head — the shop page always shows one
        if (icon == null) {
            icon = generatePixelAvatar(uuid, name);
            if (avatarWarned.add(uuid)) {
                logger.warning("Avatar for '" + (name != null ? name : uuid)
                        + "' fell back to a generated pixel head (external skin sources unreachable"
                        + " or player unknown). Put plugins/MagicWebShop/avatars/"
                        + (name != null ? name : uuid) + ".png to use a real avatar.");
            }
        }

        avatarMemory.put(uuid, icon.bytes());
        if ("image/png".equals(icon.contentType())) write(cached, icon.bytes());
        return icon;
    }

    /** A user-provided avatar from plugins/MagicWebShop/avatars/ (name or uuid). */
    private Icon localAvatar(String uuid, String name) {
        if (name != null && !name.isBlank()) {
            File f = new File(localAvatarDir, name + ".png");
            if (f.isFile()) {
                byte[] b = read(f);
                if (b != null) return new Icon(b, "image/png");
            }
        }
        File f = new File(localAvatarDir, uuid + ".png");
        if (f.isFile()) {
            byte[] b = read(f);
            if (b != null) return new Icon(b, "image/png");
        }
        return null;
    }

    /** The player's known name on this server (may be null if never joined). */
    private String playerName(String uuid) {
        try {
            return Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid)).getName();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Resolve the player's skin URL from their head/profile on this server. */
    private String fetchSkinUrlFromProfile(String uuid) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
            if (op == null || op.getName() == null) return null;
            PlayerProfile prof = op.getPlayerProfile();
            if (prof == null) return null;
            if (prof.getTextures() == null || prof.getTextures().getSkin() == null) {
                // Paper: blocking lookup of the skin for the cached player name.
                // Called via reflection so older API versions still compile/run.
                try { prof.getClass().getMethod("complete").invoke(prof); } catch (Throwable ignored) { }
            }
            if (prof.getTextures() != null && prof.getTextures().getSkin() != null) {
                return prof.getTextures().getSkin().toString();
            }
        } catch (Throwable e) {
            logger.fine("Head-profile avatar failed for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Resolve a skin URL by player NAME: Mojang name -> real account uuid ->
     * sessionserver profile -> skin texture. This is the reliable path for
     * offline-mode servers where the local uuid is not a Mojang account id.
     */
    private String fetchSkinUrlByName(String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                            + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            JsonObject profile = gson.fromJson(resp.body(), JsonObject.class);
            if (profile == null || !profile.has("id")) return null;
            String hex = profile.get("id").getAsString();
            if (hex == null || hex.length() != 32) return null;
            // 8-4-4-4-12 dashed uuid
            String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            return fetchSkinUrl(dashed);
        } catch (Exception e) {
            logger.fine("Name-based avatar failed for " + name + ": " + e.getMessage());
        }
        return null;
    }

    /** Avatar from third-party services: try by uuid, then by player name. */
    private Icon fetchThirdPartyAvatar(String uuid, String name) {
        List<String> urls = new ArrayList<>();
        urls.add("https://mc-heads.net/avatar/" + uuid);
        urls.add("https://crafatar.com/avatars/" + uuid + "?overlay");
        if (name != null && !name.isBlank()) {
            String enc = URLEncoder.encode(name, StandardCharsets.UTF_8);
            urls.add("https://mc-heads.net/avatar/" + enc);
            urls.add("https://crafatar.com/avatars/" + enc + "?overlay");
        }
        for (String url : urls) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200 && resp.body().length > 0) {
                    return new Icon(resp.body(), "image/png");
                }
            } catch (Exception e) {
                logger.fine("Third-party avatar failed " + url + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Generated Minecraft-style pixel head: an 8x8 face (hair band, skin,
     * eyes, mouth) upscaled to a crisp 128x128 PNG. Hair + skin tones are
     * derived from the player's name, so every player gets a unique avatar
     * that still reads as a "player head" even with no internet.
     */
    private Icon generatePixelAvatar(String uuid, String name) {
        int hash = (name != null ? name : uuid).hashCode();
        int[][] skinTones = {
                {0xF1, 0xC2, 0x9B}, {0xE6, 0xB0, 0x6E}, {0xC6, 0x86, 0x51},
                {0x8D, 0x5A, 0x2B}, {0x6E, 0x3B, 0x1A}, {0x2C, 0x1E, 0x16}
        };
        int[][] hairColors = {
                {0x2B, 0x2B, 0x2B}, {0x6B, 0x42, 0x1F}, {0xB0, 0x6A, 0x2A},
                {0xE0, 0xD2, 0xA8}, {0xC4, 0x5E, 0x3A}, {0x3A, 0x2A, 0x5C}
        };
        int[] skin = skinTones[Math.abs(hash) % skinTones.length];
        int[] hair = hairColors[Math.abs(hash >> 4) % hairColors.length];
        int[] eye = {0x2B, 0x2B, 0x2B};
        int[] mouth = {0x9C, 0x6B, 0x4A};

        // H = hair, S = skin, E = eye, M = mouth
        char[][] face = {
                {'H', 'H', 'H', 'H', 'H', 'H', 'H', 'H'},
                {'H', 'H', 'H', 'H', 'H', 'H', 'H', 'H'},
                {'H', 'S', 'S', 'S', 'S', 'S', 'S', 'H'},
                {'H', 'S', 'E', 'S', 'S', 'E', 'S', 'H'},
                {'H', 'S', 'S', 'S', 'S', 'S', 'S', 'H'},
                {'H', 'S', 'M', 'M', 'M', 'M', 'S', 'H'},
                {'H', 'S', 'S', 'S', 'S', 'S', 'S', 'H'},
                {'H', 'H', 'H', 'H', 'H', 'H', 'H', 'H'}
        };

        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    int[] col = switch (face[r][c]) {
                        case 'H' -> hair;
                        case 'E' -> eye;
                        case 'M' -> mouth;
                        default -> skin;
                    };
                    img.setRGB(c, r, 0xFF000000 | (col[0] << 16) | (col[1] << 8) | col[2]);
                }
            }
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 0, 0, 128, 128, null);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bos);
            return new Icon(bos.toByteArray(), "image/png");
        } catch (Exception e) {
            logger.fine("Pixel avatar generation failed: " + e.getMessage());
            // last resort: a plain tinted square
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='128' height='128' viewBox='0 0 128 128'>"
                    + "<rect width='128' height='128' fill='hsl(" + (Math.abs(hash) % 360) + ",60%,50%)'/>"
                    + "</svg>";
            return new Icon(svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
        }
    }

    /** Asks Mojang sessionserver for a player's skin texture URL by UUID. */
    private String fetchSkinUrl(String uuid) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            JsonObject profile = gson.fromJson(resp.body(), JsonObject.class);
            if (profile == null || !profile.has("properties")) return null;
            for (JsonElement el : profile.getAsJsonArray("properties")) {
                JsonObject prop = el.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;
                String b64 = prop.get("value").getAsString();
                JsonObject tex = gson.fromJson(
                        new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8), JsonObject.class);
                if (tex == null || !tex.has("textures")) return null;
                JsonObject textures = tex.getAsJsonObject("textures");
                if (textures.has("SKIN")) {
                    JsonObject skin = textures.getAsJsonObject("SKIN");
                    if (skin.has("url")) return skin.get("url").getAsString();
                }
            }
        } catch (Exception e) {
            logger.fine("Avatar profile fetch failed for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    private byte[] tryFetch(String path) {
        String url = cdnBase.replace("{version}", version) + "/" + path;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body().length > 0) return resp.body();
        } catch (Exception e) {
            logger.fine("Texture fetch failed for " + url + ": " + e.getMessage());
        }
        return null;
    }

    private byte[] read(File f) {
        try { return Files.readAllBytes(f.toPath()); } catch (Exception e) { return null; }
    }

    private void write(File f, byte[] data) {
        try { Files.write(f.toPath(), data); } catch (Exception ignored) { }
    }

    private String normalize(String material) {
        return material == null ? "stone" : material.toLowerCase().replace("minecraft:", "").trim();
    }
}
