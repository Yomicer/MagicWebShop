package com.magic.webshop.util;

import com.magic.webshop.model.Listing;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Serializes ItemStacks to a portable Base64 string (works across servers of
 * the same MC version) and extracts a display snapshot for the web UI.
 */
public final class ItemSerializer {

    private ItemSerializer() {}

    public static String toBase64(ItemStack item) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(out)) {
            boos.writeObject(item);
            boos.flush();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize item", e);
        }
    }

    public static ItemStack fromBase64(String data) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream bois = new BukkitObjectInputStream(in)) {
            return (ItemStack) bois.readObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize item", e);
        }
    }

    /** Fills the human/web facing display fields of a listing from an item. */
    public static void populateDisplay(Listing listing, ItemStack item) {
        listing.setMaterial(item.getType().name());
        listing.setAmount(item.getAmount());

        String display = Translations.displayName(item.getType().name());
        List<String> lore = new ArrayList<>();
        boolean enchanted = false;
        List<String> enchantSummary = new ArrayList<>();
        Integer customModelData = null;

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                display = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
            }
            if (meta.hasLore() && meta.getLore() != null) {
                for (String line : meta.getLore()) {
                    lore.add(org.bukkit.ChatColor.stripColor(line));
                }
            }
            Map<Enchantment, Integer> enchants = meta.getEnchants();
            if (!enchants.isEmpty()) {
                enchanted = true;
                for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                    enchantSummary.add(readableName(e.getKey().getKey().getKey()) + " " + e.getValue());
                }
            }
            if (meta.hasCustomModelData()) {
                customModelData = meta.getCustomModelData();
            }
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                try {
                    org.bukkit.profile.PlayerProfile prof = sm.getOwnerProfile();
                    if (prof != null && prof.getTextures() != null && prof.getTextures().getSkin() != null) {
                        listing.setHeadTexture(prof.getTextures().getSkin().toString());
                    }
                } catch (Throwable ignored) {
                    // older API or no profile - just skip the head texture
                }
            }
            // Modern (1.21.4+) item_model data component, read via reflection so
            // this still runs on older servers that lack the method.
            try {
                Object key = meta.getClass().getMethod("getItemModel").invoke(meta);
                if (key != null) listing.setItemModel(key.toString());
            } catch (Throwable ignored) {
                // method not present on this server version - fine
            }
        }

        listing.setDisplayName(display);
        listing.setLore(lore);
        listing.setEnchanted(enchanted);
        listing.setEnchantSummary(enchantSummary);
        listing.setCustomModelData(customModelData);
    }

    /** DIAMOND_SWORD -> "Diamond Sword". */
    public static String readableName(String raw) {
        String[] parts = raw.toLowerCase().replace("minecraft:", "").split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    /** The item's shown name: its custom display name, else the Chinese material name. */
    public static String displayNameOf(ItemStack item) {
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        }
        return Translations.displayName(item == null ? "STONE" : item.getType().name());
    }
}
