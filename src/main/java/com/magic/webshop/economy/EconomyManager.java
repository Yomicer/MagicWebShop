package com.magic.webshop.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Thin wrapper around Vault. Cross-server payouts work because each server
 * credits its OWN local player through its OWN economy; most economy plugins
 * support offline deposits.
 */
public class EconomyManager {

    private final JavaPlugin plugin;
    private Economy economy;
    private boolean available;

    public EconomyManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found - money sales disabled, barter still works.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("No Vault economy provider found - money sales disabled.");
            return false;
        }
        economy = rsp.getProvider();
        available = economy != null;
        if (available) {
            plugin.getLogger().info("Hooked into Vault economy: " + economy.getName());
        }
        return available;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return available && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!available) return false;
        EconomyResponse r = economy.withdrawPlayer(player, amount);
        return r.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!available) return false;
        EconomyResponse r = economy.depositPlayer(player, amount);
        if (!r.transactionSuccess()) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to deposit " + amount + " to " + player.getName() + ": " + r.errorMessage);
        }
        return r.transactionSuccess();
    }

    public boolean depositByUuid(UUID uuid, double amount) {
        if (!available) return false;
        return deposit(plugin.getServer().getOfflinePlayer(uuid), amount);
    }

    public String format(double amount) {
        return available ? economy.format(amount) : String.valueOf(amount);
    }
}
