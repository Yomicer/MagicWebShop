package com.magic.webshop.command;

import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.economy.EconomyManager;
import com.magic.webshop.model.Listing;
import com.magic.webshop.service.MarketService;
import com.magic.webshop.web.SessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /webshop command: sell, barter, list, cancel, web (login code), reload.
 */
public class WebShopCommand implements CommandExecutor, TabCompleter {

    private static final String P = ChatColor.AQUA + "[魔法集市] " + ChatColor.RESET;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MarketService market;
    private final SessionManager sessions;
    private final com.magic.webshop.storage.PasswordStorage passwords;
    private final com.magic.webshop.storage.DeliveryPrefStorage deliveryPrefs;
    private final EconomyManager economy;
    private final com.magic.webshop.net.NetworkInfo networkInfo;
    private final com.magic.webshop.mail.MailboxService mailbox;
    private final com.magic.webshop.update.UpdateChecker updateChecker;
    private final Runnable reloadHook;

    public WebShopCommand(JavaPlugin plugin, PluginConfig config, MarketService market,
                          SessionManager sessions, com.magic.webshop.storage.PasswordStorage passwords,
                          com.magic.webshop.storage.DeliveryPrefStorage deliveryPrefs,
                          EconomyManager economy,
                          com.magic.webshop.net.NetworkInfo networkInfo,
                          com.magic.webshop.mail.MailboxService mailbox,
                          com.magic.webshop.update.UpdateChecker updateChecker, Runnable reloadHook) {
        this.plugin = plugin;
        this.config = config;
        this.market = market;
        this.sessions = sessions;
        this.passwords = passwords;
        this.deliveryPrefs = deliveryPrefs;
        this.economy = economy;
        this.networkInfo = networkInfo;
        this.mailbox = mailbox;
        this.updateChecker = updateChecker;
        this.reloadHook = reloadHook;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("webshop.admin")) { deny(sender); return true; }
            reloadHook.run();
            sender.sendMessage(P + ChatColor.GREEN + "配置已重载。");
            return true;
        }

        if (sub.equals("sellallmaterials") || sub.equals("samm")) {
            if (!sender.hasPermission("webshop.admin")) { deny(sender); return true; }
            if (args.length < 2) {
                sender.sendMessage(P + "用法: /webshop sellallmaterials <数量>");
                return true;
            }
            int amount;
            try { amount = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { sender.sendMessage(P + ChatColor.RED + "数量必须是数字。"); return true; }
            if (amount <= 0) { sender.sendMessage(P + ChatColor.RED + "数量必须大于 0。"); return true; }
            int created = market.createSystemListings(amount);
            sender.sendMessage(P + ChatColor.GREEN + "已以「系统」卖家身份上架 " + created
                    + " 种原版物品，每种 " + amount + " 个（单价 1 元）。");
            return true;
        }

        if (sub.equals("removesysmaterials") || sub.equals("rsys")) {
            if (!sender.hasPermission("webshop.admin")) { deny(sender); return true; }
            int removed = market.removeSystemListings();
            sender.sendMessage(P + ChatColor.GREEN + "已下架所有「系统」出售的物品，共 " + removed + " 条。");
            return true;
        }

        if (sub.equals("address") || sub.equals("url") || sub.equals("info")) {
            if (!sender.hasPermission("webshop.admin")) { deny(sender); return true; }
            String url = networkInfo.resolveBaseUrl(config);
            sender.sendMessage(clickableLink("商城网址（点击打开）: ", url));
            sender.sendMessage(P + ChatColor.GRAY + "端口: " + networkInfo.getPort()
                    + " | 探测到的主机: " + networkInfo.bestHost()
                    + " | public-url: " + config.getPublicUrl());
            sender.sendMessage(P + ChatColor.GRAY + "点击上方链接即可打开，或把你的域名+端口发给玩家。");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(P + "该指令只能由玩家执行。");
            return true;
        }

        switch (sub) {
            case "web" -> doWeb(player);
            case "setpass", "setpassword", "password", "passwd" -> doSetPass(player, args);
            case "delivery", "deliver", "getmode" -> doDelivery(player, args);
            case "update", "checkupdate" -> doUpdate(player, args);
            case "sell" -> doSell(player, args);
            case "sellall" -> doSellAll(player, args);
            case "barter" -> doBarter(player, args);
            case "list" -> doList(player);
            case "cancel" -> doCancel(player, args);
            case "mailbox", "mail", "邮箱" -> mailbox.open(player);
            default -> help(player);
        }
        return true;
    }

    private void doWeb(Player player) {
        String code = sessions.issueCode(player.getUniqueId(), player.getName());
        String base = networkInfo.resolveBaseUrl(config);
        // Magic link: name + one-time code embedded so the site auto-logs in on click.
        String loginUrl = base + "/?u="
                + java.net.URLEncoder.encode(player.getName(), java.nio.charset.StandardCharsets.UTF_8)
                + "&c=" + code;
        player.sendMessage(clickableLink("点击自动登录并打开商城: ", loginUrl));
        player.sendMessage(P + ChatColor.GRAY + "（若需手动登录）网址: " + ChatColor.WHITE + base
                + ChatColor.GRAY + " 登录码: " + ChatColor.GOLD + code);
        player.sendMessage(P + ChatColor.GRAY + "登录码 5 分钟内有效，仅可使用一次。");
    }

    private void doSetPass(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(P + "用法: /webshop setpass <密码>  （4-64 位，用于网页固定密码登录）");
            return;
        }
        String pw = args[1];
        if (pw.length() < 4) { player.sendMessage(P + ChatColor.RED + "密码至少 4 位。"); return; }
        if (pw.length() > 64) { player.sendMessage(P + ChatColor.RED + "密码最多 64 位。"); return; }
        passwords.setPassword(player.getUniqueId(), pw);
        player.sendMessage(P + ChatColor.GREEN + "已设置固定登录密码。");
        player.sendMessage(P + ChatColor.GRAY + "现在打开网页，在「固定密码」方式下输入你的名字和密码即可登录。");
        player.sendMessage(P + ChatColor.GRAY + "一次性登录码（/webshop web）仍然有效。重复执行本指令可修改密码。");
    }

    private void doDelivery(Player player, String[] args) {
        String cur = deliveryPrefs.get(player.getUniqueId());
        if (cur == null) cur = config.getDeliveryDefaultMode();
        if (args.length < 2) {
            player.sendMessage(P + "当前收货方式: " + ("backpack".equals(cur) ? ChatColor.GOLD + "直接放入背包" : ChatColor.GOLD + "进邮箱"));
            player.sendMessage(P + "用法: /webshop delivery " + ChatColor.WHITE + "<backpack|mailbox>");
            player.sendMessage(ChatColor.GRAY + "  backpack = 直接推送到背包（背包装不下会自动进邮箱）");
            player.sendMessage(ChatColor.GRAY + "  mailbox  = 购买的物品全部进邮箱（/webshop mailbox 领取）");
            return;
        }
        String mode = args[1].toLowerCase();
        if (!"backpack".equals(mode) && !"mailbox".equals(mode)) {
            player.sendMessage(P + ChatColor.RED + "只能填 backpack 或 mailbox。");
            return;
        }
        deliveryPrefs.set(player.getUniqueId(), mode);
        player.sendMessage(P + ChatColor.GREEN + "已切换收货方式为: "
                + ("backpack".equals(mode) ? ChatColor.GOLD + "直接放入背包（背包满自动进邮箱）" : ChatColor.GOLD + "进邮箱"));
    }

    private void doUpdate(Player player, String[] args) {
        if (!player.hasPermission("webshop.admin")) { deny(player); return; }
        player.sendMessage(P + ChatColor.GRAY + "正在检查 GitHub 最新版本…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String res = updateChecker.checkAndMaybeUpdate();
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(P + res));
        });
    }

    /** Builds a chat line whose URL is clickable (opens the browser). */
    private Component clickableLink(String label, String url) {
        return Component.text("[魔法集市] ", NamedTextColor.AQUA)
                .append(Component.text(label, NamedTextColor.YELLOW))
                .append(Component.text(url, NamedTextColor.WHITE)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("点击打开 " + url))));
    }

    private void doSell(Player player, String[] args) {
        if (!config.isEconomyEnabled() || !economy.isAvailable()) {
            player.sendMessage(P + ChatColor.RED + "金钱交易已关闭（未接入经济插件）。请改用 /webshop barter。");
            return;
        }
        if (args.length < 2) { player.sendMessage(P + "用法: /webshop sell <一口价> [单价]"); return; }
        double price;
        try { price = Double.parseDouble(args[1]); }
        catch (NumberFormatException e) { player.sendMessage(P + ChatColor.RED + "价格必须是数字。"); return; }
        if (price <= 0) { player.sendMessage(P + ChatColor.RED + "价格必须大于 0。"); return; }
        if (config.getMaxPrice() > 0 && price > config.getMaxPrice()) {
            player.sendMessage(P + ChatColor.RED + "最高价格为 " + economy.format(config.getMaxPrice()) + "。");
            return;
        }
        ItemStack item = takeHeldItem(player);
        if (item == null) return;
        int amount = item.getAmount();
        double unitPrice;
        if (args.length >= 3) {
            try { unitPrice = Double.parseDouble(args[2]); }
            catch (NumberFormatException e) { player.sendMessage(P + ChatColor.RED + "单价必须是数字。"); player.getInventory().addItem(item); return; }
            if (unitPrice < 0) unitPrice = 0;
        } else {
            // default: derive a unit price so buyers can also purchase single items
            unitPrice = amount > 1 ? Math.round(price / amount * 100.0) / 100.0 : 0;
        }
        Listing l = market.createMoneyListing(player, item, price, unitPrice);
        player.sendMessage(P + ChatColor.GREEN + "已上架 " + amount + " 个 "
                + l.getDisplayName() + ChatColor.GREEN + "，一口价 " + economy.format(price)
                + (unitPrice > 0 ? ChatColor.GREEN + "，单价 " + economy.format(unitPrice) : "") + "。");
    }

    private void doSellAll(Player player, String[] args) {
        if (!config.isEconomyEnabled() || !economy.isAvailable()) {
            player.sendMessage(P + ChatColor.RED + "金钱交易已关闭（未接入经济插件）。");
            return;
        }
        if (args.length < 2) { player.sendMessage(P + "用法: /webshop sellall <一口价> [单价]"); return; }
        double price;
        try { price = Double.parseDouble(args[1]); }
        catch (NumberFormatException e) { player.sendMessage(P + ChatColor.RED + "价格必须是数字。"); return; }
        if (price <= 0) { player.sendMessage(P + ChatColor.RED + "价格必须大于 0。"); return; }
        if (config.getMaxPrice() > 0 && price > config.getMaxPrice()) {
            player.sendMessage(P + ChatColor.RED + "最高价格为 " + economy.format(config.getMaxPrice()) + "。");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(P + ChatColor.RED + "请把要出售的物品拿在主手上。");
            return;
        }
        if (market.countByPlayer(player.getUniqueId()) >= config.getMaxListingsPerPlayer()) {
            player.sendMessage(P + ChatColor.RED + "你已达到上架上限（" + config.getMaxListingsPerPlayer() + " 个）。");
            return;
        }
        // gather ALL items identical to the held one
        ItemStack template = hand.clone();
        template.setAmount(1);
        ItemStack[] contents = player.getInventory().getStorageContents();
        long total = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.isSimilar(template)) { total += it.getAmount(); contents[i] = null; }
        }
        if (total <= 0) { player.sendMessage(P + ChatColor.RED + "背包里没有可出售的该物品。"); return; }
        player.getInventory().setStorageContents(contents);

        int totalAmount = (int) Math.min(Integer.MAX_VALUE, total);
        double unitPrice = args.length >= 3 ? parsePrice(args[2]) : (totalAmount > 1 ? Math.round(price / totalAmount * 100.0) / 100.0 : 0);
        if (unitPrice < 0) unitPrice = 0;

        ItemStack listItem = template.clone();
        listItem.setAmount(totalAmount);
        Listing l = market.createMoneyListing(player, listItem, price, unitPrice);
        player.sendMessage(P + ChatColor.GREEN + "已上架全部 " + totalAmount + " 个 " + l.getDisplayName()
                + ChatColor.GREEN + "，一口价 " + economy.format(price)
                + (unitPrice > 0 ? ChatColor.GREEN + "，单价 " + economy.format(unitPrice) : "") + "。");
    }

    private double parsePrice(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return -1; }
    }

    private void doBarter(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(P + "用法: /webshop barter <想要物品所在快捷栏 1-9>");
            player.sendMessage(P + ChatColor.GRAY + "手上拿【要付出的物品】，把【想要的物品】放到某个快捷栏格子，输入那个格子号。");
            return;
        }
        int slot;
        try { slot = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage(P + ChatColor.RED + "请输入 1-9 的快捷栏格子号。"); return; }
        if (slot < 1 || slot > 9) { player.sendMessage(P + ChatColor.RED + "快捷栏格子号必须是 1-9。"); return; }

        int slotIndex = slot - 1;
        int heldSlot = player.getInventory().getHeldItemSlot();
        if (slotIndex == heldSlot) {
            player.sendMessage(P + ChatColor.RED + "想要的物品请放在【其它】快捷栏格子，不要和手上是同一格。");
            return;
        }
        ItemStack wanted = player.getInventory().getItem(slotIndex);
        if (wanted == null || wanted.getType().isAir()) {
            player.sendMessage(P + ChatColor.RED + "快捷栏第 " + slot + " 格没有物品。");
            return;
        }
        ItemStack wantedCopy = wanted.clone();
        int amount = wantedCopy.getAmount();

        ItemStack item = takeHeldItem(player);
        if (item == null) return;
        Listing l = market.createBarterListing(player, item, wantedCopy);
        player.sendMessage(P + ChatColor.GREEN + "已上架 " + item.getAmount() + " 个 " + l.getDisplayName()
                + ChatColor.GREEN + "，用于交换 " + amount + " 个 "
                + com.magic.webshop.util.ItemSerializer.displayNameOf(wantedCopy) + "。");
    }

    private void doList(Player player) {
        List<Listing> mine = market.catalog().stream()
                .filter(l -> l.getSellerUuid().equals(player.getUniqueId().toString())
                        && l.getServerName().equals(config.getServerName()))
                .collect(Collectors.toList());
        if (mine.isEmpty()) { player.sendMessage(P + "你当前没有上架任何物品。"); return; }
        player.sendMessage(P + ChatColor.YELLOW + "你的上架:");
        for (Listing l : mine) {
            String terms = l.getType() == com.magic.webshop.model.ListingType.MONEY
                    ? economy.format(l.getPrice())
                    : "换 " + l.getWantedAmount() + " 个 " + (l.getWantedName() != null && !l.getWantedName().isEmpty()
                        ? l.getWantedName() : com.magic.webshop.util.Translations.displayName(l.getWantedMaterial()));
            player.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + l.getAmount() + " 个 "
                    + l.getDisplayName() + ChatColor.GRAY + " (" + terms + ") 编号:" + shortId(l.getId()));
        }
        player.sendMessage(P + ChatColor.GRAY + "使用 /webshop cancel <编号> 下架");
    }

    private void doCancel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(P + "用法: /webshop cancel <编号>"); return; }
        String prefix = args[1];
        Listing match = market.catalog().stream()
                .filter(l -> l.getServerName().equals(config.getServerName())
                        && l.getSellerUuid().equals(player.getUniqueId().toString())
                        && l.getId().startsWith(prefix))
                .findFirst().orElse(null);
        if (match == null) { player.sendMessage(P + ChatColor.RED + "没有找到匹配的上架。"); return; }
        MarketService.Result r = market.cancel(match.getId(), player.getUniqueId(), false);
        player.sendMessage(P + (r.ok ? ChatColor.GREEN : ChatColor.RED) + r.message);
    }

    private ItemStack takeHeldItem(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(P + ChatColor.RED + "请把要出售的物品拿在主手上。");
            return null;
        }
        if (market.countByPlayer(player.getUniqueId()) >= config.getMaxListingsPerPlayer()) {
            player.sendMessage(P + ChatColor.RED + "你已达到上架上限（"
                    + config.getMaxListingsPerPlayer() + " 个）。");
            return null;
        }
        ItemStack copy = hand.clone();
        player.getInventory().setItemInMainHand(null);
        return copy;
    }

    private String shortId(String id) { return id.substring(0, 8); }

    private void deny(CommandSender s) { s.sendMessage(P + ChatColor.RED + "你没有权限。"); }

    private void help(CommandSender s) {
        s.sendMessage(P + ChatColor.YELLOW + "MagicWebShop 指令:");
        s.sendMessage(ChatColor.GRAY + "/webshop web " + ChatColor.WHITE + "- 获取商城网址 + 登录码");
        s.sendMessage(ChatColor.GRAY + "/webshop setpass <密码> " + ChatColor.WHITE + "- 设置网页固定登录密码（可修改）");
        s.sendMessage(ChatColor.GRAY + "/webshop delivery <backpack|mailbox> " + ChatColor.WHITE + "- 切换购买物品的收货方式（背包满自动进邮箱）");
        s.sendMessage(ChatColor.GRAY + "/webshop sell <一口价> [单价] " + ChatColor.WHITE + "- 出售手中物品（可设单价，支持按件购买）");
        s.sendMessage(ChatColor.GRAY + "/webshop sellall <一口价> [单价] " + ChatColor.WHITE + "- 出售背包内所有与手中相同的物品");
        s.sendMessage(ChatColor.GRAY + "/webshop barter <快捷栏1-9> " + ChatColor.WHITE + "- 手持付出物品，指定想要物品所在的快捷栏格子");
        s.sendMessage(ChatColor.GRAY + "/webshop list " + ChatColor.WHITE + "- 查看你的上架");
        s.sendMessage(ChatColor.GRAY + "/webshop cancel <编号> " + ChatColor.WHITE + "- 下架物品（退回邮箱）");
        s.sendMessage(ChatColor.GRAY + "/webshop mailbox " + ChatColor.WHITE + "- 打开邮箱领取物品");
        if (s.hasPermission("webshop.admin")) {
            s.sendMessage(ChatColor.GRAY + "/webshop sellallmaterials <数量> " + ChatColor.WHITE + "- （管理员）以「系统」卖家一键上架全部原版物品");
            s.sendMessage(ChatColor.GRAY + "/webshop removesysmaterials " + ChatColor.WHITE + "- （管理员）一键下架所有「系统」出售的物品");
            s.sendMessage(ChatColor.GRAY + "/webshop address " + ChatColor.WHITE + "- （管理员）查看商城网址");
            s.sendMessage(ChatColor.GRAY + "/webshop update " + ChatColor.WHITE + "- （管理员）检查 GitHub 最新版本并自动更新");
            s.sendMessage(ChatColor.GRAY + "/webshop reload " + ChatColor.WHITE + "- （管理员）重载配置");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("web", "setpass", "delivery", "sell", "sellall", "barter", "list", "cancel", "mailbox"));
            if (sender.hasPermission("webshop.admin")) { subs.add("sellallmaterials"); subs.add("removesysmaterials"); subs.add("address"); subs.add("update"); subs.add("reload"); }
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        // /webshop cancel <编号> —— 补全自己在本服的上架编号（8 位短号）
        if (args.length == 2 && "cancel".equalsIgnoreCase(args[0]) && sender instanceof Player player) {
            String prefix = args[1].toLowerCase();
            return market.catalog().stream()
                    .filter(l -> l.getServerName().equals(config.getServerName())
                            && l.getSellerUuid().equals(player.getUniqueId().toString()))
                    .map(l -> l.getId().substring(0, 8))
                    .distinct()
                    .filter(id -> id.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        // /webshop delivery <backpack|mailbox> —— 补全收货方式
        if (args.length == 2 && "delivery".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase();
            return Arrays.stream(new String[]{"backpack", "mailbox"})
                    .filter(m -> m.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
