package com.voxticket.service;

import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;

/**
 * Translates internal enum values into natural language BEFORE they ever
 * reach the model, so the LLM doesn't have to interpret raw values like
 * "AUTHORIZED" or "PARTIALLY_REFUNDED" itself. Raw enums remain the
 * authoritative values everywhere else (business logic, persistence,
 * audit) - this class only produces display text for AI-facing DTOs.
 */
public final class CustomerFriendlyLanguage {

    private CustomerFriendlyLanguage() {
    }

    public static String describe(OrderStatus status) {
        return switch (status) {
            case OPEN -> "in progress";
            case CANCELLED -> "cancelled";
            case COMPLETED -> "completed";
        };
    }

    public static String describe(FulfillmentStatus status) {
        return switch (status) {
            case UNFULFILLED -> "not yet shipped";
            case PARTIALLY_FULFILLED -> "partially shipped";
            case FULFILLED -> "shipped";
        };
    }

    public static String describe(PaymentMethod method) {
        return switch (method) {
            case CARD -> "card";
            case WALLET -> "wallet";
            case BANK_TRANSFER -> "bank transfer";
            case COD -> "cash on delivery";
        };
    }

    public static String describe(PaymentStatus status) {
        return switch (status) {
            case PENDING -> "pending";
            case AUTHORIZED -> "authorized but not yet charged";
            case PAID -> "paid";
            case FAILED -> "failed";
            case VOIDED -> "voided";
            case PARTIALLY_REFUNDED -> "partially refunded";
            case REFUNDED -> "fully refunded";
        };
    }

    public static String describe(ShipmentStatus status) {
        return switch (status) {
            case LABEL_CREATED -> "label created, not yet shipped";
            case IN_TRANSIT -> "in transit";
            case OUT_FOR_DELIVERY -> "out for delivery";
            case ATTEMPTED_DELIVERY -> "a delivery attempt was made";
            case DELIVERED -> "delivered";
            case EXCEPTION -> "there's a delivery issue";
            case LOST -> "lost in transit";
        };
    }

    public static String describe(ReturnStatus status) {
        return switch (status) {
            case REQUESTED -> "requested";
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case IN_TRANSIT -> "on its way back to us";
            case RECEIVED -> "received";
            case INSPECTED -> "inspected";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
        };
    }

    public static String describe(ReturnReason reason) {
        return switch (reason) {
            case CHANGED_MIND -> "changed mind";
            case WRONG_SIZE -> "wrong size";
            case NOT_AS_DESCRIBED -> "not as described";
            case DEFECTIVE -> "defective";
            case DAMAGED -> "damaged";
            case OTHER -> "other";
        };
    }

    public static String describe(RefundStatus status) {
        return switch (status) {
            case PENDING -> "pending";
            case SUCCEEDED -> "completed";
            case FAILED -> "failed";
        };
    }

    public static String describe(ClaimStatus status) {
        return switch (status) {
            case OPEN -> "open";
            case IN_REVIEW -> "being reviewed";
            case RESOLVED -> "resolved";
            case REJECTED -> "rejected";
        };
    }

    public static String describe(ClaimReason reason) {
        return switch (reason) {
            case DAMAGED -> "damaged item";
            case DEFECTIVE -> "defective item";
            case WRONG_ITEM -> "wrong item received";
            case MISSING_ITEM -> "missing item";
            case OTHER -> "other issue";
        };
    }

    public static String describe(ClaimResolution resolution) {
        return switch (resolution) {
            case REFUND -> "refund";
            case REPLACEMENT -> "replacement";
            case MANUAL_REVIEW -> "manual review";
        };
    }
}