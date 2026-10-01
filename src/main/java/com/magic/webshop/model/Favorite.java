package com.magic.webshop.model;

/**
 * A favorited seller entry, stored per web user (viewer uuid). Plain POJO so it
 * persists as JSON via Gson.
 */
public class Favorite {

    private String uuid;
    private String name;
    private long createdAt;

    public Favorite() {
        // for Gson
    }

    public Favorite(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
