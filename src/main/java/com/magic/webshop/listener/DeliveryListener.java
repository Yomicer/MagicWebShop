package com.magic.webshop.listener;

import com.magic.webshop.service.DeliveryService;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Delivers items that were bought/bartered while the player was offline.
 */
public class DeliveryListener implements Listener {

    private final JavaPlugin plugin;
    private final DeliveryService delivery;

    public DeliveryListener(JavaPlugin plugin, DeliveryService delivery) {
        this.plugin = plugin;
        this.delivery = delivery;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Slight delay so the inventory is fully ready on join.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int delivered = delivery.flush(event.getPlayer());
            if (delivered > 0) {
                event.getPlayer().sendMessage(ChatColor.AQUA + "[魔法集市] " + ChatColor.GREEN
                        + "你收到了集市送来的 " + delivered + " 件物品。");
            }
        }, 20L);
    }
}
