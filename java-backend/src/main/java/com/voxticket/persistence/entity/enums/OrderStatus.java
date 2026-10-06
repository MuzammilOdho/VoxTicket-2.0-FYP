package com.voxticket.persistence.entity.enums;

/**
 * Top-level order lifecycle only. Deliberately excludes SHIPPED, DELIVERED,
 * REFUNDED - those belong to Shipment/Payment/Refund state (spec §24).
 */
public enum OrderStatus { OPEN, CANCELLED, COMPLETED }