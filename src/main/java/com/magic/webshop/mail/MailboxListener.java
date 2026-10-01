package com.magic.webshop.mail;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Click-to-claim mailbox: every click inside the mailbox is cancelled; clicking
 * an item slot claims it, clicking the nav arrows changes page.
 */
public class MailboxListener implements Listener {

    private final MailboxService mailbox;

    public MailboxListener(MailboxService mailbox) {
        this.mailbox = mailbox;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MailboxHolder holder)) return;
        int raw = event.getRawSlot();
        // Any interaction with the mailbox (top) inventory is controlled by us.
        if (raw < 0 || raw >= event.getInventory().getSize()) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (MailboxService.isNavSlot(raw)) {
            mailbox.navigate(p, holder, raw);
        } else {
            mailbox.claim(p, holder, raw);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MailboxHolder) {
            event.setCancelled(true);
        }
    }
}
