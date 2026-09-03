package com.voxticket.policy;

public enum ReturnDenialReason {
    ITEM_NOT_DELIVERED,
    RETURN_WINDOW_EXPIRED,
    ITEM_FINAL_SALE,
    ITEM_NOT_RETURNABLE,
    NO_REMAINING_RETURNABLE_QUANTITY
}