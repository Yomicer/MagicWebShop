package com.magic.webshop.mail;

import com.magic.webshop.config.PluginConfig;
import com.magic.webshop.util.ItemSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paged, click-to-claim mailbox. Items are stored as single stacks whose amount
 * may exceed the vanilla limit (merged per item type), so a huge quantity uses
 * ONE slot. Clicking a slot claims as much as fits in the player's inventory;
 * the remainder stays in the mailbox.
 */
public class MailboxService {

    public static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45, SLOT_INFO = 49, SLOT_NEXT = 53;

    private final JavaPlugin plugin;
    private final MailboxStorage storage;
    private final PluginConfig config;

    public MailboxService(JavaPlugin plugin, MailboxStorage storage, PluginConfig config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
    }

    /** Add an item to the mailbox, merging into an existing identical stack. */
    public synchronized void add(UUID uuid, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        String key = uuid.toString();
        List<String> full = storage.get(key);
        for (int i = 0; i < full.size(); i++) {
            try {
                ItemStack e = ItemSerializer.fromBase64(full.get(i));
                if (e.isSimilar(item)) {
                    long sum = (long) e.getAmount() + item.getAmount();
                    e.setAmount((int) Math.min(Integer.MAX_VALUE, sum));
                    full.set(i, ItemSerializer.toBase64(e));
                    storage.set(key, full);
                    return;
                }
            } catch (Exception ignored) { }
        }
        full.add(ItemSerializer.toBase64(item));
        storage.set(key, full);
    }

    public int count(UUID uuid) {
        return storage.count(uuid.toString());
    }

    public void notify(UUID uuid, String message) {
        Runnable r = () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(Component.text("[魔法集市] ", NamedTextColor.AQUA)
                        .append(Component.text(message, NamedTextColor.GREEN)));
            }
        };
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    public void open(Player player) { openPage(player, 0); }

    public void openPage(Player player, int page) {
        List<String> full = storage.get(player.getUniqueId().toString());
        int maxPages = config.getMailboxMaxPages();
        int totalPages = Math.max(1, Math.min(maxPages, (int) Math.ceil(full.size() / (double) PAGE_SIZE)));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int pageStart = page * PAGE_SIZE;
        int shown = Math.max(0, Math.min(PAGE_SIZE, full.size() - pageStart));

        MailboxHolder holder = new MailboxHolder(player.getUniqueId(), page, pageStart, shown, totalPages);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("邮箱 · 第 " + (page + 1) + "/" + totalPages + " 页 (点击领取)", NamedTextColor.DARK_AQUA));
        holder.setInventory(inv);

        for (int i = 0; i < shown; i++) {
            try { inv.setItem(i, ItemSerializer.fromBase64(full.get(pageStart + i))); }
            catch (Exception ignored) { }
        }
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = PAGE_SIZE; s < 54; s++) inv.setItem(s, filler);
        if (page > 0) inv.setItem(SLOT_PREV, named(Material.ARROW, "§a上一页"));
        if (page < totalPages - 1) inv.setItem(SLOT_NEXT, named(Material.ARROW, "§a下一页"));
        inv.setItem(SLOT_INFO, named(Material.BOOK,
                "§b第 " + (page + 1) + "/" + totalPages + " 页 §7(共 " + full.size() + " 项) · 点击物品领取"));
        player.openInventory(inv);
    }

    public static boolean isNavSlot(int slot) { return slot >= PAGE_SIZE && slot < 54; }
    public static int slotPrev() { return SLOT_PREV; }
    public static int slotNext() { return SLOT_NEXT; }

    /** Nav click: open the previous/next page next tick. */
    public void navigate(Player player, MailboxHolder holder, int slot) {
        int target = slot == SLOT_PREV ? holder.getPage() - 1
                : slot == SLOT_NEXT ? holder.getPage() + 1 : -1;
        if (target < 0 || target >= holder.getTotalPages()) return;
        Bukkit.getScheduler().runTask(plugin, () -> openPage(player, target));
    }

    /** Click-to-claim: give the player as much of the clicked entry as fits. */
    public void claim(Player player, MailboxHolder holder, int slotIndex) {
        int globalIndex = holder.getPageStart() + slotIndex;
        String key = player.getUniqueId().toString();
        List<String> full = storage.get(key);
        if (globalIndex < 0 || globalIndex >= full.size()) return;

        ItemStack entry;
        try { entry = ItemSerializer.fromBase64(full.get(globalIndex)); }
        catch (Exception e) { return; }

        int total = entry.getAmount();
        int maxS = Math.max(1, entry.getMaxStackSize());
        int remaining = total;
        while (remaining > 0) {
            ItemStack chunk = entry.clone();
            chunk.setAmount(Math.min(remaining, maxS));
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(chunk);
            int notAdded = leftover.isEmpty() ? 0 : leftover.values().iterator().next().getAmount();
            int added = chunk.getAmount() - notAdded;
            remaining -= added;
            if (added == 0) break; // inventory full
        }
        int claimed = total - remaining;
        if (claimed <= 0) {
            player.sendMessage(ChatColor.RED + "[魔法集市] 背包已满，无法领取。");
            return;
        }
        // update storage + the open GUI slot in place
        if (remaining > 0) {
            entry.setAmount(remaining);
            full.set(globalIndex, ItemSerializer.toBase64(entry));
            holder.getInventory().setItem(slotIndex, entry.clone());
        } else {
            full.remove(globalIndex);
            holder.getInventory().setItem(slotIndex, null);
            Bukkit.getScheduler().runTask(plugin, () -> openPage(player, holder.getPage())); // re-flow page
        }
        storage.set(key, full);
        player.sendMessage(ChatColor.AQUA + "[魔法集市] " + ChatColor.GREEN + "领取了 " + claimed + " × "
                + ItemSerializer.displayNameOf(entry)
                + (remaining > 0 ? ChatColor.YELLOW + "（背包已满，剩余 " + remaining + " 留在邮箱）" : ""));
    }

    private ItemStack named(Material material, String name) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); it.setItemMeta(meta); }
        return it;
    }
}
