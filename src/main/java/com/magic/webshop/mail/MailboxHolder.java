package com.magic.webshop.mail;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an inventory as a player's mailbox page and remembers where this page
 * sits in the full mail list, so takes can be merged back correctly.
 */
public class MailboxHolder implements InventoryHolder {

    private final UUID owner;
    private final int page;
    private final int pageStart;      // index of the first item shown on this page
    private final int shownCount;     // how many item slots this page originally filled
    private final int totalPages;
    private Inventory inventory;

    public MailboxHolder(UUID owner, int page, int pageStart, int shownCount, int totalPages) {
        this.owner = owner;
        this.page = page;
        this.pageStart = pageStart;
        this.shownCount = shownCount;
        this.totalPages = totalPages;
    }

    public UUID getOwner() { return owner; }
    public int getPage() { return page; }
    public int getPageStart() { return pageStart; }
    public int getShownCount() { return shownCount; }
    public int getTotalPages() { return totalPages; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
