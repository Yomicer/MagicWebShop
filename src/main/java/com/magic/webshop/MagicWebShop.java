package com.magic.webshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.magic.webshop.command.WebShopCommand;
import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.economy.EconomyManager;
import com.magic.webshop.listener.DeliveryListener;
import com.magic.webshop.net.NetworkInfo;
import com.magic.webshop.net.PeerClient;
import com.magic.webshop.service.DeliveryService;
import com.magic.webshop.service.MarketService;
import com.magic.webshop.storage.ListingStorage;
import com.magic.webshop.storage.PendingStorage;
import com.magic.webshop.storage.PlayerStatsStorage;
import com.magic.webshop.storage.FavoritesStorage;
import com.magic.webshop.storage.PasswordStorage;
import com.magic.webshop.web.SessionManager;
import com.magic.webshop.web.TextureService;
import com.magic.webshop.web.WebServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * MagicWebShop — a cross-server marketplace with a built-in web UI.
 */
public class MagicWebShop extends JavaPlugin {

    private final Gson gson = new GsonBuilder().create();

    private PluginConfig config;
    private EconomyManager economy;
    private ListingStorage listingStorage;
    private PendingStorage pendingStorage;
    private PeerClient peerClient;
    private NetworkInfo networkInfo;
    private WebServer webServer;
    private com.magic.webshop.web.NettyPortSharer portSharer;
    private com.magic.webshop.web.TextureService textures;
    private com.magic.webshop.mail.MailboxStorage mailboxStorage;
    private com.magic.webshop.storage.PlayerStatsStorage statsStorage;
    private com.magic.webshop.storage.FavoritesStorage favoritesStorage;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        config = new PluginConfig();
        config.reload(this);

        com.magic.webshop.util.Translations.init(resolvePackDir(), getLogger());

        economy = new EconomyManager(this);
        if (config.isEconomyEnabled()) economy.setup();

        listingStorage = new ListingStorage(getDataFolder(), getLogger());
        listingStorage.load();
        pendingStorage = new PendingStorage(getDataFolder(), getLogger());
        pendingStorage.load();
        mailboxStorage = new com.magic.webshop.mail.MailboxStorage(getDataFolder(), getLogger());
        mailboxStorage.load();
        statsStorage = new PlayerStatsStorage(getDataFolder(), getLogger());
        statsStorage.load();
        favoritesStorage = new FavoritesStorage(getDataFolder(), getLogger());
        favoritesStorage.load();

        peerClient = new PeerClient(config, gson, getLogger());
        peerClient.rebuildAddressMap();

        networkInfo = new NetworkInfo(getLogger());
        networkInfo.detectAsync();

        SessionManager sessions = new SessionManager();
        PasswordStorage passwords = new PasswordStorage(getDataFolder(), getLogger(),
                name -> { org.bukkit.OfflinePlayer op = getServer().getOfflinePlayer(name); return op == null ? null : op.getUniqueId(); });
        DeliveryService delivery = new DeliveryService(this, pendingStorage);
        com.magic.webshop.mail.MailboxService mailbox =
                new com.magic.webshop.mail.MailboxService(this, mailboxStorage, config);
        com.magic.webshop.storage.DeliveryPrefStorage deliveryPrefs =
                new com.magic.webshop.storage.DeliveryPrefStorage(getDataFolder(), getLogger());
        com.magic.webshop.update.UpdateChecker updateChecker =
                new com.magic.webshop.update.UpdateChecker(this, config);
        // periodic auto-update check (every 6 hours) when enabled
        if (config.isAutoUpdate()) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                String res = updateChecker.checkAndMaybeUpdate();
                if (res.contains("新版本")) getLogger().info("MagicWebShop: " + res);
            }, 20L * 60 * 30, 20L * 60 * 60 * 6);
        }
        MarketService market = new MarketService(this, config, listingStorage, economy,
                peerClient, delivery, mailbox, deliveryPrefs, statsStorage);

        textures = createTextureService();

        com.magic.webshop.web.RequestRouter router = new com.magic.webshop.web.RequestRouter(
                config, gson, listingStorage, market, sessions, passwords, textures, favoritesStorage, getLogger());

        boolean shared = false;
        if (config.isShareServerPort()) {
            portSharer = new com.magic.webshop.web.NettyPortSharer(this, router, getLogger());
            shared = portSharer.enable();
            if (shared) {
                networkInfo.setPort(getServer().getPort()); // web is served on the game port
            } else {
                portSharer = null; // fell back; start the standalone server below
            }
        }

        if (!shared) {
            webServer = new WebServer(config, router, getLogger());
            try {
                webServer.start();
            } catch (Exception e) {
                getLogger().severe("Could not start web server on port " + config.getWebPort() + ": " + e.getMessage());
                getLogger().severe("Is the port already in use? Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            networkInfo.setPort(webServer.getBoundPort());
        }

        WebShopCommand cmd = new WebShopCommand(this, config, market, sessions, passwords, deliveryPrefs, economy,
                networkInfo, mailbox, updateChecker, this::reloadPlugin);
        getCommand("webshop").setExecutor(cmd);
        getCommand("webshop").setTabCompleter(cmd);

        getServer().getPluginManager().registerEvents(new DeliveryListener(this, delivery), this);
        getServer().getPluginManager().registerEvents(
                new com.magic.webshop.mail.MailboxListener(mailbox), this);

        long period = config.getPeerRefreshSeconds() * 20L;
        refreshTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, () -> peerClient.refresh(), 40L, period);

        getLogger().info("MagicWebShop enabled as '" + config.getServerName() + "' with "
                + config.getPeers().size() + " peer(s).");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (webServer != null) webServer.stop();
        if (portSharer != null) portSharer.disable();
        if (listingStorage != null) listingStorage.save();
        if (pendingStorage != null) pendingStorage.save();
        if (mailboxStorage != null) mailboxStorage.save();
        if (statsStorage != null) statsStorage.save();
        if (favoritesStorage != null) favoritesStorage.save();
        getLogger().info("MagicWebShop disabled.");
    }

    /** Reload config, peers, and resource-pack textures at runtime. Port/bind changes need a restart. */
    private void reloadPlugin() {
        config.reload(this);
        peerClient.rebuildAddressMap();
        if (config.isEconomyEnabled() && !economy.isAvailable()) economy.setup();
        com.magic.webshop.util.Translations.init(resolvePackDir(), getLogger());
        if (textures != null) {
            textures.reload(buildResolver(), config.getTextureCdnBase(), config.getTextureVersion());
        }
    }

    private com.magic.webshop.web.ResourcePackResolver buildResolver() {
        java.io.File packDir = resolvePackDir();
        com.magic.webshop.web.ResourcePackResolver resolver =
                new com.magic.webshop.web.ResourcePackResolver(packDir, gson, getLogger());
        if (resolver.available()) {
            getLogger().info("Resource pack textures enabled from: " + packDir.getAbsolutePath());
        } else {
            String dir = config.getResourcePackDir();
            if (dir != null && !dir.isBlank()) {
                getLogger().warning("Resource pack dir '" + dir + "' has no 'assets' folder - using vanilla textures.");
            }
        }
        return resolver;
    }

    /** The resource-pack folder: the configured one, or an auto-detected common folder. */
    private java.io.File resolvePackDir() {
        String dir = config.getResourcePackDir();
        return (dir == null || dir.isBlank()) ? autoDetectPack() : new java.io.File(dir);
    }

    /** When resource-pack-dir is blank, look for a pack in a few common folders. */
    private java.io.File autoDetectPack() {
        String[] candidates = {
                "texture_resource", "resourcepack", "resource_pack", "resourcepacks",
                new java.io.File(getDataFolder(), "resourcepack").getPath()
        };
        for (String c : candidates) {
            java.io.File f = new java.io.File(c);
            if (new java.io.File(f, "assets").isDirectory()) {
                getLogger().info("Auto-detected resource pack folder: " + f.getAbsolutePath());
                return f;
            }
        }
        return null;
    }

    private com.magic.webshop.web.TextureService createTextureService() {
        return new com.magic.webshop.web.TextureService(getDataFolder(),
                config.getTextureCdnBase(), config.getTextureVersion(), buildResolver(), getLogger());
    }
}
