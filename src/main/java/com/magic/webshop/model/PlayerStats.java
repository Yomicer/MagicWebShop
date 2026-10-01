package com.magic.webshop.model;

/**
 * Cumulative marketplace statistics for one player. Tracks how much this player
 * has listed, sold and earned across the network. Sent between servers as JSON
 * via Gson and cached by peers, so keep it a plain POJO.
 */
public class PlayerStats {

    private String uuid;
    /** Latest known display name (kept so shops can still show a name). */
    private String name;

    /** Total number of listings created (cumulative, never decreases). */
    private int listedCount;
    /** Total number of completed money sales (成交次数). */
    private int soldCount;
    /** Total quantity of items sold across all money sales (卖出物品数量). */
    private int soldItems;
    /** Total money earned by the seller after fees (累计收益). */
    private double earned;
    /** Total completed barter trades (换物次数). */
    private int barterCount;

    private long firstSeen;
    private long lastSeen;

    public PlayerStats() {
        // for Gson
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getListedCount() { return listedCount; }
    public void setListedCount(int listedCount) { this.listedCount = listedCount; }

    public int getSoldCount() { return soldCount; }
    public void setSoldCount(int soldCount) { this.soldCount = soldCount; }

    public int getSoldItems() { return soldItems; }
    public void setSoldItems(int soldItems) { this.soldItems = soldItems; }

    public double getEarned() { return earned; }
    public void setEarned(double earned) { this.earned = earned; }

    public int getBarterCount() { return barterCount; }
    public void setBarterCount(int barterCount) { this.barterCount = barterCount; }

    public long getFirstSeen() { return firstSeen; }
    public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    /** Merge another stats snapshot (e.g. from a peer server) into this one. */
    public void merge(PlayerStats other) {
        if (other == null) return;
        this.listedCount += other.listedCount;
        this.soldCount += other.soldCount;
        this.soldItems += other.soldItems;
        this.earned += other.earned;
        this.barterCount += other.barterCount;
        if (other.name != null && !other.name.isBlank()) this.name = other.name;
        if (this.firstSeen == 0 || other.firstSeen < this.firstSeen) this.firstSeen = other.firstSeen;
        if (other.lastSeen > this.lastSeen) this.lastSeen = other.lastSeen;
    }
}
