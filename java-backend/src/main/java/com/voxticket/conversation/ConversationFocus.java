package com.voxticket.conversation;

/**
 * Replaces two separate lastDiscussedOrderNumber/lastDiscussedItemDescription fields with one
 * cohesive value object. Carries the authoritative item SKU Java already resolved - not just
 * display text - so a later "yes, return it" can reuse the exact item without re-running fuzzy
 * name matching a second time. Deliberately has no expiry/staleness tracking - nothing observed
 * requires it.
 */
public record ConversationFocus(String orderNumber, String itemSku, String itemDisplayName) {

    public static ConversationFocus ofOrder(String orderNumber) {
        return new ConversationFocus(orderNumber, null, null);
    }

    public ConversationFocus withItem(String itemSku, String itemDisplayName) {
        return new ConversationFocus(this.orderNumber, itemSku, itemDisplayName);
    }
}