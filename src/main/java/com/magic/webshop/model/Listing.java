package com.magic.webshop.model;

import java.util.List;
import java.util.UUID;

/**
 * A single marketplace listing. This POJO is persisted to disk and sent over
 * the wire between servers as JSON (via Gson), so keep it plain.
 *
 * <p>Display fields ({@link #material}, {@link #displayName}, ...) are a
 * denormalised snapshot taken when the item was listed. They let peers and the
 * web UI render the item without needing to deserialize the full ItemStack.
 * The authoritative item to deliver on purchase lives in {@link #itemData}.
 */
public class Listing {

    private String id;
    private String serverName;

    private String sellerUuid;
    private String sellerName;

    private ListingType type;

    /** Base64-encoded ItemStack that the buyer receives. */
    private String itemData;

    // ---- display snapshot ----
    private String material;
    private String displayName;
    private int amount;
    private List<String> lore;
    private boolean enchanted;
    private List<String> enchantSummary;
    private Integer customModelData;
    /** For PLAYER_HEAD: the skin texture URL (textures.minecraft.net/...), if any. */
    private String headTexture;
    /** Modern (1.21.4+) item_model component, e.g. "namespace:path", if set. */
    private String itemModel;

    // ---- MONEY sales ----
    private double price;
    /** Per-item price (单价). 0 = buying single units is disabled. */
    private double unitPrice;
    /** The stack size when first listed; used to scale the flat price as stock sells. */
    private int originalAmount;

    // ---- BARTER sales ----
    private String wantedMaterial;
    private int wantedAmount;
    /** Base64 of the wanted item as a template (amount 1) for exact matching. */
    private String wantedItemData;
    /** Display name of the wanted item (custom name or Chinese material name). */
    private String wantedName;

    private long createdAt;

    public Listing() {
        // for Gson
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getSellerUuid() { return sellerUuid; }
    public void setSellerUuid(String sellerUuid) { this.sellerUuid = sellerUuid; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public ListingType getType() { return type; }
    public void setType(ListingType type) { this.type = type; }

    public String getItemData() { return itemData; }
    public void setItemData(String itemData) { this.itemData = itemData; }

    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public List<String> getLore() { return lore; }
    public void setLore(List<String> lore) { this.lore = lore; }

    public boolean isEnchanted() { return enchanted; }
    public void setEnchanted(boolean enchanted) { this.enchanted = enchanted; }

    public List<String> getEnchantSummary() { return enchantSummary; }
    public void setEnchantSummary(List<String> enchantSummary) { this.enchantSummary = enchantSummary; }

    public Integer getCustomModelData() { return customModelData; }
    public void setCustomModelData(Integer customModelData) { this.customModelData = customModelData; }

    public String getHeadTexture() { return headTexture; }
    public void setHeadTexture(String headTexture) { this.headTexture = headTexture; }

    public String getItemModel() { return itemModel; }
    public void setItemModel(String itemModel) { this.itemModel = itemModel; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }

    public int getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(int originalAmount) { this.originalAmount = originalAmount; }

    public String getWantedMaterial() { return wantedMaterial; }
    public void setWantedMaterial(String wantedMaterial) { this.wantedMaterial = wantedMaterial; }

    public int getWantedAmount() { return wantedAmount; }
    public void setWantedAmount(int wantedAmount) { this.wantedAmount = wantedAmount; }

    public String getWantedItemData() { return wantedItemData; }
    public void setWantedItemData(String wantedItemData) { this.wantedItemData = wantedItemData; }

    public String getWantedName() { return wantedName; }
    public void setWantedName(String wantedName) { this.wantedName = wantedName; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
