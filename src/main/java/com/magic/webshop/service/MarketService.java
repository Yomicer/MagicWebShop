package com.magic.webshop.service;

import com.google.gson.JsonObject;
import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.economy.EconomyManager;
import com.magic.webshop.model.Listing;
import com.magic.webshop.model.ListingType;
import com.magic.webshop.model.PlayerStats;
import com.magic.webshop.net.PeerClient;
import com.magic.webshop.storage.ListingStorage;
import com.magic.webshop.storage.PlayerStatsStorage;
import com.magic.webshop.util.ItemSerializer;
import com.magic.webshop.web.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Core marketplace logic: create listings, aggregate the catalog, and run
 * purchases (both same-server and cross-server). Purchase methods are invoked
 * from web-server threads, never the main thread, so it is safe for them to
 * block on {@link #sync(Callable)} and on peer HTTP calls.
 */
public class MarketService {

    /** Simple result carrier returned to the web layer. */
    public static final class Result {
        public final boolean ok;
        public final String message;
        private Result(boolean ok, String message) { this.ok = ok; this.message = message; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final ListingStorage listings;
    private final EconomyManager economy;
    private final PeerClient peers;
    private final DeliveryService delivery;
    private final com.magic.webshop.mail.MailboxService mailbox;
    private final com.magic.webshop.storage.DeliveryPrefStorage deliveryPrefs;
    private final PlayerStatsStorage stats;
    private final Random random = new Random();

    /** Seller identity for bulk "system shop" listings (owned by nobody). */
    public static final String SYSTEM_SELLER_UUID = "00000000-0000-0000-0000-000000000000";

    public MarketService(JavaPlugin plugin, PluginConfig config, ListingStorage listings, EconomyManager economy,
                         PeerClient peers, DeliveryService delivery,
                         com.magic.webshop.mail.MailboxService mailbox,
                         com.magic.webshop.storage.DeliveryPrefStorage deliveryPrefs,
                         PlayerStatsStorage stats) {
        this.plugin = plugin;
        this.config = config;
        this.listings = listings;
        this.economy = economy;
        this.peers = peers;
        this.delivery = delivery;
        this.mailbox = mailbox;
        this.deliveryPrefs = deliveryPrefs;
        this.stats = stats;
    }

    // ------------------------------------------------------------------ listings

    /** The combined catalog = this server's listings + cached peer listings. */
    public List<Listing> catalog() {
        List<Listing> all = listings.getAll();
        all.addAll(peers.getRemoteListings());
        return all;
    }

    public Listing createMoneyListing(Player seller, ItemStack item, double price, double unitPrice) {
        Listing l = baseListing(seller, item);
        l.setType(ListingType.MONEY);
        l.setPrice(price);
        l.setUnitPrice(unitPrice);
        l.setOriginalAmount(item.getAmount());
        listings.add(l);
        stats.recordListing(l.getSellerUuid(), l.getSellerName());
        announce(l);
        return l;
    }

    /** The current "buy all" flat price for the remaining stock. */
    public double currentFlat(Listing l) {
        double base = l.getPrice();
        int orig = l.getOriginalAmount() > 0 ? l.getOriginalAmount() : l.getAmount();
        int rem = l.getAmount();
        double flat = (config.isFlatFollowsStock() && orig > 0) ? base * rem / orig : base;
        return Math.round(flat * 100.0) / 100.0;
    }

    public Listing createBarterListing(Player seller, ItemStack item, ItemStack wanted) {
        Listing l = baseListing(seller, item);
        l.setType(ListingType.BARTER);
        ItemStack tmpl = wanted.clone();
        int wantAmount = tmpl.getAmount();
        tmpl.setAmount(1);
        l.setWantedItemData(ItemSerializer.toBase64(tmpl));
        l.setWantedMaterial(wanted.getType().name());
        l.setWantedAmount(wantAmount);
        l.setWantedName(ItemSerializer.displayNameOf(wanted));
        listings.add(l);
        stats.recordListing(l.getSellerUuid(), l.getSellerName());
        announce(l);
        return l;
    }

    private Listing baseListing(Player seller, ItemStack item) {
        Listing l = new Listing();
        l.setId(Listing.newId());
        l.setServerName(config.getServerName());
        l.setSellerUuid(seller.getUniqueId().toString());
        l.setSellerName(seller.getName());
        l.setItemData(ItemSerializer.toBase64(item));
        l.setCreatedAt(System.currentTimeMillis());
        ItemSerializer.populateDisplay(l, item);
        return l;
    }

    /**
     * Bulk-lists every vanilla material (item-form) as a "system shop" listing
     * owned by nobody (seller name 系统, nil uuid). These skip the announce
     * broadcast and player statistics. Each item is priced at 1 per unit.
     *
     * @param amount quantity of each material to list (capped at stack size)
     * @return how many listings were created
     */
    public int createSystemListings(int amount) {
        if (amount <= 0) return 0;
        int created = 0;
        for (Material m : Material.values()) {
            if (m.isAir() || m.isLegacy() || !m.isItem()) continue;
            try {
                int n = Math.min(amount, m.getMaxStackSize());
                if (n <= 0) continue;
                ItemStack item = new ItemStack(m, n);
                Listing l = new Listing();
                l.setId(Listing.newId());
                l.setServerName(config.getServerName());
                l.setSellerUuid(SYSTEM_SELLER_UUID);
                l.setSellerName("系统");
                l.setType(ListingType.MONEY);
                l.setItemData(ItemSerializer.toBase64(item));
                l.setAmount(n);
                l.setOriginalAmount(n);
                l.setPrice(n * 1.0);   // 1 元/件
                l.setUnitPrice(1.0);
                l.setCreatedAt(System.currentTimeMillis());
                ItemSerializer.populateDisplay(l, item);
                listings.add(l); // no announce() -> no chat spam for hundreds of listings
                created++;
            } catch (Exception ignored) { }
        }
        return created;
    }

    /** True for bulk "system shop" listings that belong to nobody. */
    public static boolean isSystemListing(Listing l) {
        String s = l == null ? null : l.getSellerUuid();
        return s != null && s.startsWith("00000000-0000-0000-0000-");
    }

    /** Removes every system-shop listing. Returns how many were removed. */
    public int removeSystemListings() {
        int removed = 0;
        for (Listing l : listings.getAll()) {
            if (isSystemListing(l)) {
                listings.remove(l.getId());
                removed++;
            }
        }
        return removed;
    }

    /** Credits the seller (no-op for system listings: the server keeps the money). */
    private void paySeller(Listing l, double amount) {
        if (isSystemListing(l)) return;
        economy.depositByUuid(UUID.fromString(l.getSellerUuid()), amount);
    }

    /** Notifies the seller (no-op for system listings). */
    private void notifySeller(Listing l, String message) {
        if (isSystemListing(l)) return;
        mailbox.notify(UUID.fromString(l.getSellerUuid()), message);
    }

    public long countByPlayer(UUID uuid) {
        return listings.countByPlayer(uuid.toString());
    }

    /** Cancel a listing owned by the player (or by an admin). Returns the item. */
    public Result cancel(String listingId, UUID requester, boolean admin) {
        Listing l = listings.get(listingId);
        if (l == null) return Result.fail("本服未找到该上架。");
        if (!admin && !l.getSellerUuid().equals(requester.toString())) {
            return Result.fail("这不是你的上架。");
        }
        listings.remove(listingId);
        if (isSystemListing(l)) {
            return Result.ok("已下架。");
        }
        UUID seller = UUID.fromString(l.getSellerUuid());
        mailbox.add(seller, ItemSerializer.fromBase64(l.getItemData()));
        mailbox.notify(seller, "你的商品「" + l.getDisplayName() + "」已下架，物品已放入邮箱，使用 /webshop mailbox 领取。");
        return Result.ok("已下架，物品已放入邮箱（使用 /webshop mailbox 领取）。");
    }

    // ------------------------------------------------------------------ broadcast

    /** Random gate for the configurable 0-100 broadcast chances. */
    private boolean roll(int chancePercent) {
        return chancePercent >= 100 || (chancePercent > 0 && random.nextInt(100) < chancePercent);
    }

    /** Broadcast a new listing locally and to all peer servers (chance-gated). */
    private void announce(Listing l) {
        if (!roll(config.getListingBroadcastChance())) return;
        String terms = l.getType() == ListingType.MONEY
                ? "售价 " + displayPrice(l.getPrice())
                : "换 " + l.getWantedAmount() + "× " + wantedNameOf(l);
        broadcast(listingLines(l.getServerName(), l.getSellerName(), l.getAmount(), l.getDisplayName(), terms));

        JsonObject payload = new JsonObject();
        payload.addProperty("server", l.getServerName());
        payload.addProperty("player", l.getSellerName());
        payload.addProperty("amount", l.getAmount());
        payload.addProperty("itemName", l.getDisplayName());
        payload.addProperty("terms", terms);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> peers.announceToPeers(payload));
    }

    /** Chance-gated money-sale broadcast (runs on the seller's server). */
    private void broadcastSale(Listing l, String buyerName, int amount, double price) {
        if (!roll(config.getSaleBroadcastChance())) return;
        broadcast(saleLines(buyerName, l.getSellerName(), amount, l.getDisplayName(),
                displayPrice(price)));
    }

    /** Chance-gated barter-trade broadcast (runs on the seller's server). */
    private void broadcastBarter(Listing l, String buyerName) {
        if (!roll(config.getBarterBroadcastChance())) return;
        broadcast(barterLines(buyerName, l.getSellerName(), l.getAmount(), l.getDisplayName(),
                l.getWantedAmount() + "× " + wantedNameOf(l)));
    }

    /** Called when a peer tells us one of ITS players listed something. */
    public void receiveAnnounce(JsonObject p) {
        try {
            broadcast(listingLines(p.get("server").getAsString(), p.get("player").getAsString(),
                    p.get("amount").getAsInt(), p.get("itemName").getAsString(), p.get("terms").getAsString()));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ broadcast templates
    // Each broadcast picks one template at random from its list, so the chat
    // feels varied instead of repeating the same line every time.

    private static final List<String> LISTING_TEMPLATES = List.of(
            "§f     ♡ §e{server} §7的玩家 §a{player} §f上架了 §b{amount}× §f{item} §7— §d{terms}",
            "§f     ♡ §e{server} §7新上架：§a{player} §7摆出 §b{amount}× §f{item} §7，§d{terms}",
            "§f     ♡ §a{player} §7在 §e{server} §7上架了 §b{amount}× §f{item} §7 · §d{terms}",
            "§f     ♡ §d新货 §7· §e{server} §7的 §a{player} §7出售 §b{amount}× §f{item} §7，标价 §d{terms}",
            "§f     ♡ §a{player} §7@ §e{server} §7上新：§b{amount}× §f{item} §7— §d{terms}"
    );

    private static final List<String> SALE_TEMPLATES = List.of(
            "§f     ♡ §a{buyer} §7以 §d{terms} §7购得 §b{amount}× §f{item} §7，卖家 §e{seller}",
            "§f     ♡ §e{seller} §7的 §b{amount}× §f{item} §7被 §a{buyer} §7买下，§d{terms}",
            "§f     ♡ §d成交 §7· §a{buyer} §7购得 §b{amount}× §f{item} §7，卖家 §e{seller} §7，§d{terms}",
            "§f     ♡ §a{buyer} §7在集市淘到 §b{amount}× §f{item} §7，来自 §e{seller} §7— §d{terms}",
            "§f     ♡ §a{buyer} §7买走 §e{seller} §7的 §b{amount}× §f{item} §7，§d{terms}"
    );

    private static final List<String> BARTER_TEMPLATES = List.of(
            "§f     ♡ §a{buyer} §7用 §d{terms} §7换走 §e{seller} §7的 §b{amount}× §f{item}",
            "§f     ♡ §e{seller} §7的 §b{amount}× §f{item} §7被 §a{buyer} §7以 §d{terms} §7换走",
            "§f     ♡ §d换物达成 §7· §a{buyer} §7⇄§e{seller} §7：§b{amount}× §f{item}",
            "§f     ♡ §a{buyer} §7交出 §d{terms} §7，换来 §e{seller} §7的 §b{amount}× §f{item}",
            "§f     ♡ §d交换成功 §7· §b{amount}× §f{item} §7从 §e{seller} §7流向 §a{buyer} §7"
    );

    /** Cute 3-line broadcast built from a random listing template. */
    private List<String> listingLines(String server, String player, int amount, String item, String terms) {
        String mid = render(tpl(LISTING_TEMPLATES), new java.util.HashMap<>() {{
            put("server", server); put("player", player);
            put("amount", String.valueOf(amount)); put("item", item); put("terms", terms);
        }});
        return lines(mid);
    }

    /** Cute 3-line broadcast built from a random sale template. */
    private List<String> saleLines(String buyer, String seller, int amount, String item, String terms) {
        String mid = render(tpl(SALE_TEMPLATES), new java.util.HashMap<>() {{
            put("buyer", buyer); put("seller", seller);
            put("amount", String.valueOf(amount)); put("item", item); put("terms", terms);
        }});
        return lines(mid);
    }

    /** Cute 3-line broadcast built from a random barter template. */
    private List<String> barterLines(String buyer, String seller, int amount, String item, String terms) {
        String mid = render(tpl(BARTER_TEMPLATES), new java.util.HashMap<>() {{
            put("buyer", buyer); put("seller", seller);
            put("amount", String.valueOf(amount)); put("item", item); put("terms", terms);
        }});
        return lines(mid);
    }

    /** Pick one random template from a list. */
    private String tpl(List<String> templates) {
        return templates.get(random.nextInt(templates.size()));
    }

    /** Replace {placeholder} tokens in a template. */
    private static String render(String template, java.util.Map<String, String> values) {
        String s = template;
        for (java.util.Map.Entry<String, String> e : values.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private static List<String> lines(String mid) {
        String top = "§d§l✧ ･ ~ ─────────── §b§l♡ 魔法集市 ♡ §r §d§l─────────── ~ ･ ✧";
        String bot = "§d§l✧ ･ ~ ──────────── ✦ ˚ ♡ ˚ ✦ ──────────── ~ ･ ✧";
        return List.of(top, mid, bot);
    }

    /**
     * Clean money display for chat. Vault's formatter can be verbose or
     * parenthesized (e.g. "(1.00 dollar)") which looks broken in a broadcast
     * line; strip brackets and collapse whitespace.
     */
    private String displayPrice(double price) {
        String f = economy.format(price);
        f = f.replace("(", "").replace(")", "").replaceAll("\\s+", " ").trim();
        return f.isEmpty() ? String.valueOf(price) : f;
    }

    private void broadcast(List<String> lines) {
        Runnable r = () -> { for (String line : lines) plugin.getServer().broadcastMessage(line); };
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    // ------------------------------------------------------------------ purchase

    /** Entry point used by the web layer when a logged-in player clicks buy. */
    public Result purchase(SessionManager.Session buyer, String listingId, String listingServer, int quantity) {
        UUID buyerUuid;
        try {
            buyerUuid = UUID.fromString(buyer.uuid);
        } catch (Exception e) {
            return Result.fail("会话无效。");
        }
        boolean local = listingServer.equals(config.getServerName());
        Listing snap = local ? listings.get(listingId) : findRemote(listingId, listingServer);
        if (snap == null) return Result.fail("该商品已不存在。");
        if (buyerUuid.toString().equals(snap.getSellerUuid()) && !config.isAllowSelfPurchase()) {
            return Result.fail("不能购买自己的上架。");
        }
        try {
            return local ? purchaseLocal(buyerUuid, buyer.name, snap, quantity)
                         : purchaseRemote(buyer, buyerUuid, snap, quantity);
        } catch (Exception e) {
            plugin.getLogger().warning("Purchase failed: " + e.getMessage());
            return Result.fail("交易出错: " + e.getMessage());
        }
    }

    private Result purchaseLocal(UUID buyerUuid, String buyerName, Listing snap, int quantity) {
        final String id = snap.getId();
        if (snap.getType() == ListingType.MONEY) {
            OfflinePlayer buyerOff = Bukkit.getOfflinePlayer(buyerUuid);
            return sync(() -> {
                Listing l = listings.get(id);
                if (l == null) return Result.fail("商品已售出。");
                if (!economy.isAvailable()) return Result.fail("经济系统不可用。");
                int remaining = l.getAmount();
                int qty = quantity <= 0 ? remaining : Math.min(quantity, remaining);
                if (qty <= 0) return Result.fail("数量无效。");
                boolean buyAll = qty >= remaining;
                double cost;
                if (buyAll) {
                    cost = currentFlat(l);
                } else {
                    if (l.getUnitPrice() <= 0) return Result.fail("该商品未设置单价，只能整堆购买。");
                    cost = Math.round(l.getUnitPrice() * qty * 100.0) / 100.0;
                }
                if (!economy.has(buyerOff, cost)) return Result.fail("余额不足。");
                if (!economy.withdraw(buyerOff, cost)) return Result.fail("扣款失败。");
                double got = proceeds(cost);
                paySeller(l, got);
                ItemStack give = ItemSerializer.fromBase64(l.getItemData());
                give.setAmount(qty);
                deliverPurchased(buyerUuid, give, "你购买的「" + l.getDisplayName() + "」×" + qty);
                if (buyAll) listings.remove(id); else reduceListing(l, remaining - qty);
                stats.recordSale(l.getSellerUuid(), l.getSellerName(), qty, got);
                notifySeller(l, "你的商品「" + l.getDisplayName() + "」被 " + buyerName
                        + " 购买 " + qty + " 件，获得 " + economy.format(got) + "。");
                broadcastSale(l, buyerName, qty, cost);
                return Result.ok("购买 " + qty + " 件成功，花费 " + economy.format(cost)
                        + "，物品已放入邮箱（/webshop mailbox 领取）。");
            });
        }
        // BARTER (same server): buyer must be online to hand over the wanted item.
        return sync(() -> {
            Player p = Bukkit.getPlayer(buyerUuid);
            if (p == null) return Result.fail("以物换物需要你在游戏内在线。");
            Listing l = listings.get(id);
            if (l == null) return Result.fail("商品已售出。");
            ItemStack tmpl = wantedTemplate(l);
            if (tmpl == null) return Result.fail("该商品想要的物品无效。");
            int need = l.getWantedAmount();
            if (countSimilar(p, tmpl) < need) {
                return Result.fail("你需要 " + need + " 个 " + wantedNameOf(l) + "（需与要求的物品完全一致）。");
            }
            removeSimilar(p, tmpl, need);
            listings.remove(id);
            mailbox.add(buyerUuid, ItemSerializer.fromBase64(l.getItemData()));
            mailbox.notify(buyerUuid, "你换到的「" + l.getDisplayName() + "」已放入邮箱，使用 /webshop mailbox 领取。");
            ItemStack pay = tmpl.clone(); pay.setAmount(need);
            if (!isSystemListing(l)) {
                UUID seller = UUID.fromString(l.getSellerUuid());
                mailbox.add(seller, pay);
                mailbox.notify(seller, "你的商品「" + l.getDisplayName() + "」被 " + buyerName + " 换走，换得的 "
                        + need + "× " + wantedNameOf(l) + " 已放入邮箱。");
            }
            stats.recordBarter(l.getSellerUuid(), l.getSellerName());
            broadcastBarter(l, buyerName);
            return Result.ok("交易完成，换到的物品已放入邮箱（/webshop mailbox 领取）。");
        });
    }

    /**
     * Deliver a purchased item to the buyer following their delivery preference
     * (backpack = direct to inventory, mailbox = always into the mailbox).
     * If backpack mode and the inventory is full or the player is offline,
     * the item automatically goes into the mailbox.
     * Must run on the main thread (inventory access).
     */
    private void deliverPurchased(UUID buyerUuid, ItemStack item, String what) {
        String mode = deliveryPrefs.get(buyerUuid);
        if (mode == null) mode = config.getDeliveryDefaultMode();
        if ("backpack".equals(mode)) {
            Player online = Bukkit.getPlayer(buyerUuid);
            if (online != null && online.isOnline()) {
                var left = online.getInventory().addItem(item);
                if (!left.isEmpty()) {
                    for (ItemStack l : left.values()) mailbox.add(buyerUuid, l);
                    mailbox.notify(buyerUuid, what + " 背包已满，多余部分已放入邮箱（/webshop mailbox 领取）。");
                } else {
                    mailbox.notify(buyerUuid, what + " 已放入背包。");
                }
            } else {
                mailbox.add(buyerUuid, item);
                mailbox.notify(buyerUuid, what + " 你当前离线，物品已放入邮箱（/webshop mailbox 领取）。");
            }
        } else {
            mailbox.add(buyerUuid, item);
            mailbox.notify(buyerUuid, what + " 已放入邮箱（/webshop mailbox 领取）。");
        }
    }

    /** Reduce a listing's stock in place (updates the stored item + amount). */
    private void reduceListing(Listing l, int newAmount) {
        try {
            ItemStack stack = ItemSerializer.fromBase64(l.getItemData());
            stack.setAmount(Math.max(1, newAmount));
            l.setItemData(ItemSerializer.toBase64(stack));
        } catch (Exception ignored) { }
        l.setAmount(newAmount);
        listings.add(l); // overwrite by id + save
    }

    private double proceeds(double price) {
        double fee = Math.max(0, Math.min(100, config.getSellFeePercent()));
        return price * (1.0 - fee / 100.0);
    }

    private Listing findRemote(String listingId, String server) {
        for (Listing l : peers.getRemoteListings()) {
            if (l.getId().equals(listingId) && l.getServerName().equals(server)) return l;
        }
        return null;
    }

    private boolean hasItems(Player p, Material material, int amount) {
        int count = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == material) count += it.getAmount();
        }
        return count >= amount;
    }

    private void removeItems(Player p, Material material, int amount) {
        p.getInventory().removeItem(new ItemStack(material, amount));
    }

    // ---- exact-item (NBT/model aware) matching for barter ----

    /** The wanted item as a single-unit template (custom data preserved). */
    private ItemStack wantedTemplate(Listing l) {
        if (l.getWantedItemData() != null && !l.getWantedItemData().isEmpty()) {
            try {
                ItemStack t = ItemSerializer.fromBase64(l.getWantedItemData());
                t.setAmount(1);
                return t;
            } catch (Exception ignored) { }
        }
        Material m = Material.matchMaterial(l.getWantedMaterial());
        return m == null ? null : new ItemStack(m);
    }

    private int countSimilar(Player p, ItemStack tmpl) {
        int count = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.isSimilar(tmpl)) count += it.getAmount();
        }
        return count;
    }

    private void removeSimilar(Player p, ItemStack tmpl, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it != null && it.isSimilar(tmpl)) {
                int take = Math.min(left, it.getAmount());
                it.setAmount(it.getAmount() - take);
                p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                left -= take;
            }
        }
    }

    /** Split a total quantity of the template into properly sized stacks. */
    private List<ItemStack> splitStacks(ItemStack tmpl, int total) {
        List<ItemStack> out = new java.util.ArrayList<>();
        int max = tmpl.getMaxStackSize() <= 0 ? 64 : tmpl.getMaxStackSize();
        int left = total;
        while (left > 0) {
            ItemStack s = tmpl.clone();
            int n = Math.min(left, max);
            s.setAmount(n);
            out.add(s);
            left -= n;
        }
        return out;
    }

    private String wantedNameOf(Listing l) {
        if (l.getWantedName() != null && !l.getWantedName().isEmpty()) return l.getWantedName();
        return com.magic.webshop.util.Translations.displayName(l.getWantedMaterial());
    }

    private Result purchaseRemote(SessionManager.Session buyer, UUID buyerUuid, Listing snap, int quantity) {
        OfflinePlayer buyerOff = Bukkit.getOfflinePlayer(buyerUuid);

        if (snap.getType() == ListingType.MONEY) {
            int remaining = snap.getAmount();
            int qty = quantity <= 0 ? remaining : Math.min(quantity, remaining);
            if (qty <= 0) return Result.fail("数量无效。");
            boolean buyAll = qty >= remaining;
            double cost;
            if (buyAll) cost = currentFlat(snap);
            else {
                if (snap.getUnitPrice() <= 0) return Result.fail("该商品未设置单价，只能整堆购买。");
                cost = Math.round(snap.getUnitPrice() * qty * 100.0) / 100.0;
            }
            final double charged = cost;
            // 1) take money locally
            Result pay = sync(() -> {
                if (!economy.isAvailable()) return Result.fail("经济系统不可用。");
                if (!economy.has(buyerOff, charged)) return Result.fail("余额不足。");
                if (!economy.withdraw(buyerOff, charged)) return Result.fail("扣款失败。");
                return Result.ok("paid");
            });
            if (!pay.ok) return pay;

            // 2) finalise on the seller's server (HTTP, off main thread)
            JsonObject req = request(snap, buyer, null);
            req.addProperty("quantity", qty);
            req.addProperty("buyAll", buyAll);
            req.addProperty("charged", charged);
            JsonObject resp = peers.completeSale(snap.getServerName(), req);
            if (resp == null || !resp.has("ok") || !resp.get("ok").getAsBoolean()) {
                sync(() -> { economy.deposit(buyerOff, charged); return null; }); // refund
                return Result.fail(reason(resp, "卖家服务器拒绝了本次交易（已退款）。"));
            }
            sync(() -> { deliverPurchased(buyerUuid,
                    ItemSerializer.fromBase64(resp.get("soldItem").getAsString()),
                    "你购买的「" + snap.getDisplayName() + "」×" + qty); return null; });
            return Result.ok("购买 " + qty + " 件成功，花费 " + economy.format(charged)
                    + "，物品已送达（详见游戏内提示）。");
        }

        // BARTER across servers: take the wanted (exact) item from the (online) buyer.
        ItemStack tmpl = wantedTemplate(snap);
        if (tmpl == null) return Result.fail("该商品想要的物品无效。");
        final int amount = snap.getWantedAmount();
        Result take = sync(() -> {
            Player p = Bukkit.getPlayer(buyerUuid);
            if (p == null) return Result.fail("以物换物需要你在游戏内在线。");
            if (countSimilar(p, tmpl) < amount) {
                return Result.fail("你需要 " + amount + " 个 " + wantedNameOf(snap) + "（需与要求的物品完全一致）。");
            }
            removeSimilar(p, tmpl, amount);
            return Result.ok("taken");
        });
        if (!take.ok) return take;

        ItemStack offeredStack = tmpl.clone();
        offeredStack.setAmount(amount);
        JsonObject resp = peers.completeSale(snap.getServerName(),
                request(snap, buyer, ItemSerializer.toBase64(offeredStack)));
        if (resp == null || !resp.has("ok") || !resp.get("ok").getAsBoolean()) {
            for (ItemStack s : splitStacks(tmpl, amount)) delivery.deliver(buyerUuid, s); // give it back
            return Result.fail(reason(resp, "卖家服务器拒绝了本次交易（物品已退还）。"));
        }
        mailbox.add(buyerUuid, ItemSerializer.fromBase64(resp.get("soldItem").getAsString()));
        mailbox.notify(buyerUuid, "你换到的物品已放入邮箱，使用 /webshop mailbox 领取。");
        return Result.ok("交易完成，换到的物品已放入邮箱（/webshop mailbox 领取）。");
    }

    private JsonObject request(Listing snap, SessionManager.Session buyer, String offeredItem) {
        JsonObject o = new JsonObject();
        o.addProperty("listingId", snap.getId());
        o.addProperty("type", snap.getType().name());
        o.addProperty("buyerUuid", buyer.uuid);
        o.addProperty("buyerName", buyer.name);
        o.addProperty("buyerServer", config.getServerName());
        if (offeredItem != null) o.addProperty("offeredItem", offeredItem);
        return o;
    }

    private String reason(JsonObject resp, String fallback) {
        if (resp != null && resp.has("error")) return resp.get("error").getAsString();
        return fallback;
    }

    /**
     * Invoked (via the web API) when a REMOTE server's buyer purchases one of
     * OUR listings. We validate + remove the listing, pay/queue for our seller,
     * and return the sold item for the buyer's server to deliver.
     */
    public JsonObject completeSale(JsonObject req) {
        final String id = req.get("listingId").getAsString();
        final String type = req.get("type").getAsString();
        final String buyerName = req.has("buyerName") ? req.get("buyerName").getAsString() : "买家";
        return sync(() -> {
            JsonObject out = new JsonObject();
            Listing l = listings.get(id);
            if (l == null) {
                out.addProperty("ok", false);
                out.addProperty("error", "该商品已被售出或下架。");
                return out;
            }
            UUID seller = isSystemListing(l) ? null : UUID.fromString(l.getSellerUuid());
            if ("MONEY".equals(type)) {
                int remaining = l.getAmount();
                boolean buyAll = req.has("buyAll") && req.get("buyAll").getAsBoolean();
                int qty = req.has("quantity") ? Math.min(req.get("quantity").getAsInt(), remaining) : remaining;
                if (qty <= 0) qty = remaining;
                if (buyAll) qty = remaining;
                double charged = req.has("charged") ? req.get("charged").getAsDouble() : l.getPrice();
                double got = proceeds(charged);
                paySeller(l, got);
                ItemStack give = ItemSerializer.fromBase64(l.getItemData());
                give.setAmount(qty);
                if (buyAll || qty >= remaining) listings.remove(id); else reduceListing(l, remaining - qty);
                stats.recordSale(l.getSellerUuid(), l.getSellerName(), qty, got);
                notifySeller(l, "你的商品「" + l.getDisplayName() + "」被 " + buyerName
                        + " 购买 " + qty + " 件，获得 " + economy.format(got) + "。");
                broadcastSale(l, buyerName, qty, charged);
                out.addProperty("ok", true);
                out.addProperty("soldItem", ItemSerializer.toBase64(give));
                return out;
            } else {
                ItemStack offered = ItemSerializer.fromBase64(req.get("offeredItem").getAsString());
                listings.remove(id);
                if (!isSystemListing(l)) {
                    mailbox.add(seller, offered);
                    mailbox.notify(seller, "你的商品「" + l.getDisplayName() + "」被 " + buyerName
                            + " 换走，换得的物品已放入邮箱（/webshop mailbox 领取）。");
                }
                stats.recordBarter(l.getSellerUuid(), l.getSellerName());
                broadcastBarter(l, buyerName);
                out.addProperty("ok", true);
                out.addProperty("soldItem", l.getItemData());
                return out;
            }
        });
    }

    private <T> T sync(Callable<T> task) {
        if (Bukkit.isPrimaryThread()) {
            try { return task.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ statistics

    /** All local player statistics (served to peers via /api/stats). */
    public List<PlayerStats> localStats() {
        return stats.getAll();
    }

    /**
     * Aggregated stats for one player across this server and all cached peers
     * (their stats are pulled periodically, like the remote listings). Returns
     * null when the player has no recorded activity anywhere.
     */
    public PlayerStats aggregateStats(String uuid) {
        PlayerStats local = stats.get(uuid);
        PlayerStats merged = local != null ? copyOf(local) : new PlayerStats();
        merged.setUuid(uuid);
        for (PlayerStats p : peers.getRemoteStats()) {
            if (uuid.equals(p.getUuid())) merged.merge(p);
        }
        boolean empty = merged.getListedCount() == 0 && merged.getSoldCount() == 0
                && merged.getBarterCount() == 0 && merged.getName() == null;
        return empty ? null : merged;
    }

    private PlayerStats copyOf(PlayerStats s) {
        PlayerStats c = new PlayerStats();
        c.setUuid(s.getUuid());
        c.setListedCount(s.getListedCount());
        c.setSoldCount(s.getSoldCount());
        c.setSoldItems(s.getSoldItems());
        c.setEarned(s.getEarned());
        c.setBarterCount(s.getBarterCount());
        c.setName(s.getName());
        c.setFirstSeen(s.getFirstSeen());
        c.setLastSeen(s.getLastSeen());
        return c;
    }
}
