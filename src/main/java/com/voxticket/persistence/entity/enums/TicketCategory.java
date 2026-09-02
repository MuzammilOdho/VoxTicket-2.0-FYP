package com.voxticket.persistence.entity.enums;

/** Resolved decision: keep this small; claim-specific reasons live on OrderClaim.reason, not here. */
public enum TicketCategory { GENERAL, COMPLAINT, DELIVERY, PAYMENT, RETURN, REFUND, CLAIM, ESCALATION }