package com.voxticket.conversation;

import java.math.BigDecimal;
import java.time.Instant;

/** Spec §15. E.g. REFUND_INITIATED / ORD-10003 / PKR 18,500 / RFN-00015. */
public record RecentAction(RecentActionType type, String target, String status, BigDecimal amount, String reference, Instant timestamp) {
}