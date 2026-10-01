package com.magic.webshop.service;

import com.magic.webshop.storage.PendingStorage;
import com.magic.webshop.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Hands items to players. Bukkit inventory calls must run on the main thread,
 * so delivery is always scheduled there. Offline recipients get their items
 * queued in {@link PendingStorage} and receive them on next join.
 */
public class DeliveryService {

    private final JavaPlugin plugin;
    private final PendingStorage pending;

    public DeliveryService(JavaPlugin plugin, PendingStorage pending) {
        this.plugin = plugin;
        this.pending = pending;
    }

    /** Deliver a single item to a player by UUID (may be called off-thread). */
    public void deliver(UUID uuid, ItemStack item) {
        if (Bukkit.isPrimaryThread()) {
            dispatch(uuid, item);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> dispatch(uuid, item));
        }
    }

    private void dispatch(UUID uuid, ItemStack item) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            giveOrDrop(online, item);
        } else {
            pending.queue(uuid.toString(), ItemSerializer.toBase64(item));
        }
    }

    /** Called from PlayerJoinEvent to flush any queued items. */
    public int flush(Player player) {
        List<String> items = pending.drain(player.getUniqueId().toString());
        for (String b64 : items) {
            try {
                giveOrDrop(player, ItemSerializer.fromBase64(b64));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deliver a queued item to "
                        + player.getName() + ": " + e.getMessage());
            }
        }
        return items.size();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        var overflow = player.getInventory().addItem(item);
        overflow.values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
    }
}
