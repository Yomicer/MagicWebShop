package com.magic.webshop.config;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed view over config.yml. Reloadable via {@link #reload(Plugin)}.
 */
public class PluginConfig {

    public static final class Peer {
        public final String name;
        /** One or more host:port links to reach this peer; tried in order (link aggregation). */
        public final List<String> addresses;
        public Peer(String name, List<String> addresses) {
            this.name = name;
            this.addresses = addresses;
        }
    }

    private String serverName;
    private int webPort;
    private String publicUrl;
    private String bind;
    private boolean shareServerPort;
    private int shopRowsPerPage;
    private String shopDefaultView;
    private String deliveryDefaultMode;
    private boolean autoUpdate;
    private String updateRepo;
    private String updateMirror;
    private String networkSecret;
    private final List<Peer> peers = new ArrayList<>();
    private int peerRefreshSeconds;

    private boolean economyEnabled;
    private String currencySymbol;
    private double sellFeePercent;
    private boolean flatFollowsStock;

    private String textureCdnTemplate;
    private String textureCdnBase;
    private String textureVersion;
    private String resourcePackDir;

    private int maxListingsPerPlayer;
    private double maxPrice;
    private int mailboxMaxPages;

    private boolean allowSelfPurchase;

    private int listingBroadcastChance;
    private int saleBroadcastChance;
    private int barterBroadcastChance;

    public void reload(Plugin plugin) {
        mergeMissingConfigKeys(plugin);
        plugin.reloadConfig();
        var c = plugin.getConfig();

        serverName = c.getString("server-name", "Server-1");
        webPort = c.getInt("web.port", 8085);
        publicUrl = c.getString("web.public-url", "auto");
        bind = c.getString("web.bind", "0.0.0.0");
        shareServerPort = c.getBoolean("web.share-server-port", false);
        shopRowsPerPage = Math.max(1, c.getInt("web.shop-rows-per-page", 7));
        shopDefaultView = "loadmore".equalsIgnoreCase(c.getString("web.shop-default-view", "pages"))
                ? "loadmore" : "pages";
        deliveryDefaultMode = "backpack".equalsIgnoreCase(c.getString("delivery.default-mode", "mailbox"))
                ? "backpack" : "mailbox";
        autoUpdate = c.getBoolean("auto-update", false);
        updateRepo = c.getString("update-repo", "https://github.com/Yomicer/MagicWebShop");
        updateMirror = c.getString("update-mirror", "");
        networkSecret = c.getString("network-secret", "change-me");
        peerRefreshSeconds = Math.max(5, c.getInt("peer-refresh-seconds", 15));

        peers.clear();
        List<?> raw = c.getList("peers");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof java.util.Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n == null) continue;
                    List<String> addrs = new ArrayList<>();
                    Object list = m.get("addresses");
                    if (list instanceof List<?> la) {
                        for (Object a : la) if (a != null) addrs.add(String.valueOf(a));
                    }
                    Object single = m.get("address");
                    if (single != null) addrs.add(String.valueOf(single));
                    if (!addrs.isEmpty()) peers.add(new Peer(String.valueOf(n), addrs));
                }
            }
        }

        economyEnabled = c.getBoolean("economy.enabled", true);
        currencySymbol = c.getString("economy.currency-symbol", "$");
        sellFeePercent = c.getDouble("economy.sell-fee-percent", 0);
        flatFollowsStock = c.getBoolean("economy.flat-follows-stock", true);

        textureCdnTemplate = c.getString("texture.cdn-template", "");
        textureCdnBase = c.getString("texture.cdn-base",
                "https://assets.mcasset.cloud/{version}/assets/minecraft/textures");
        textureVersion = c.getString("texture.version", "1.20.4");
        resourcePackDir = c.getString("texture.resource-pack-dir", "");

        maxListingsPerPlayer = c.getInt("limits.max-listings-per-player", 27);
        maxPrice = c.getDouble("limits.max-price", 0);
        mailboxMaxPages = Math.max(1, c.getInt("mailbox.max-pages", 5));
        allowSelfPurchase = c.getBoolean("limits.allow-self-purchase", false);

        listingBroadcastChance = clampChance(c.getInt("broadcast.listing-chance", 100));
        saleBroadcastChance = clampChance(c.getInt("broadcast.sale-chance", 100));
        barterBroadcastChance = clampChance(c.getInt("broadcast.barter-chance", 100));
    }

    private int clampChance(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /**
     * Old config.yml files are never overwritten by saveDefaultConfig(), so
     * keys added in later plugin versions would be missing on disk. This merges
     * every missing key (nested under its original section, comments included)
     * from the BUNDLED config.yml into the existing file, keeping the admin's
     * own values intact. Also removes the dotted-key block that older builds
     * may have appended. Idempotent across reloads.
     */
    private void mergeMissingConfigKeys(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return;
        try {
            // heal files that got the old dotted-key append block
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String marker = "# ===== 以下为插件新增的配置项（自动补全） =====";
            int idx = raw.indexOf(marker);
            if (idx >= 0) {
                Files.writeString(file.toPath(), raw.substring(0, idx).stripTrailing() + "\n",
                        StandardCharsets.UTF_8);
            }
            // load the admin's file with UTF-8 so Chinese comments survive
            org.bukkit.configuration.file.YamlConfiguration disk =
                    new org.bukkit.configuration.file.YamlConfiguration();
            disk.options().parseComments(true);
            try (java.io.Reader r = new java.io.InputStreamReader(
                    Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                disk.load(r);
            }
            // bundled defaults as the source of new keys
            org.bukkit.configuration.file.YamlConfiguration def;
            try (InputStream in = plugin.getResource("config.yml")) {
                if (in == null) return;
                def = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            // merge missing keys (properly nested) + keep existing comments, then save UTF-8
            disk.setDefaults(def);
            disk.options().copyDefaults(true);
            Files.writeString(file.toPath(), disk.saveToString(), StandardCharsets.UTF_8);
            plugin.getLogger().info("MagicWebShop: merged new config keys into config.yml");
        } catch (Exception e) {
            plugin.getLogger().warning("MagicWebShop: could not merge new config keys: " + e.getMessage());
        }
    }

    public String getServerName() { return serverName; }
    public int getWebPort() { return webPort; }
    public String getPublicUrl() { return publicUrl; }
    public String getBind() { return bind; }
    public boolean isShareServerPort() { return shareServerPort; }
    public int getShopRowsPerPage() { return shopRowsPerPage; }
    public String getShopDefaultView() { return shopDefaultView; }
    public String getDeliveryDefaultMode() { return deliveryDefaultMode; }
    public boolean isAutoUpdate() { return autoUpdate; }
    public String getUpdateRepo() { return updateRepo; }
    public String getUpdateMirror() { return updateMirror; }
    public String getNetworkSecret() { return networkSecret; }
    public List<Peer> getPeers() { return peers; }

    /** True if this server has a peer configured with the given server name.
     *  Cross-server trading is only allowed between mutually-configured servers. */
    public boolean hasPeer(String name) {
        if (name == null || name.isBlank()) return false;
        for (Peer p : peers) if (name.equals(p.name)) return true;
        return false;
    }
    public int getPeerRefreshSeconds() { return peerRefreshSeconds; }
    public boolean isEconomyEnabled() { return economyEnabled; }
    public String getCurrencySymbol() { return currencySymbol; }
    public double getSellFeePercent() { return sellFeePercent; }
    public boolean isFlatFollowsStock() { return flatFollowsStock; }
    public String getTextureCdnTemplate() { return textureCdnTemplate; }
    public String getTextureCdnBase() { return textureCdnBase; }
    public String getTextureVersion() { return textureVersion; }
    public String getResourcePackDir() { return resourcePackDir; }
    public int getMaxListingsPerPlayer() { return maxListingsPerPlayer; }
    public double getMaxPrice() { return maxPrice; }
    public int getMailboxMaxPages() { return mailboxMaxPages; }
    public boolean isAllowSelfPurchase() { return allowSelfPurchase; }
    public int getListingBroadcastChance() { return listingBroadcastChance; }
    public int getSaleBroadcastChance() { return saleBroadcastChance; }
    public int getBarterBroadcastChance() { return barterBroadcastChance; }
}
