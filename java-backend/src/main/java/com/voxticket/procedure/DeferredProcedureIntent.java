package com.voxticket.procedure;

import com.voxticket.identity.VerifiedOrderRef;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pass 2D-B: a single deferred customer mutation intent.
 *
 * <p>This is deliberately NOT a {@link ProcedureState}. It represents only
 * that the customer asked for another action while one procedure is already
 * active. It is structurally incapable of carrying authorization:
 * <ul>
 *   <li>no OTP, no OTP hash, no challenge id;</li>
 *   <li>no verification state, no confirmation state;</li>
 *   <li>no {@code ProcedureState}, no procedure id;</li>
 *   <li>no execution authority, no mutation result.</li>
 * </ul>
 *
 * <p>It cannot execute directly. When promoted it is treated as a brand-new
 * procedure start: ownership is re-resolved, business eligibility is
 * re-checked, and fresh OTP / fresh confirmation is required. Promotion goes
 * through the normal public start methods, so it can never bypass ownership
 * checks.
 *
 * <p>Final shape. Every intent carries the customer-visible selectors
 * ({@code orderNumber}, {@code itemDisplayName}) plus the internal equality
 * keys ({@code orderId}, {@code itemSku}) and the raw customer-supplied
 * action detail needed to restart the request. No secrets are ever stored
 * here. Fields that were not yet known when the request was deferred are
 * {@code null}:
 * <ul>
 *   <li>{@code orderId} is always present - the order is resolved (ownership
 *       checked) before anything is deferred;</li>
 *   <li>{@code itemSku} is {@code null} when the item was not resolved yet;
 *       {@code itemDisplayName} then holds the raw customer-supplied item
 *       reference (or {@code null} when none was named);</li>
 *   <li>{@code reasonName} is the parsed reason enum name when the reason /
 *       problem text was already supplied, else {@code null};</li>
 *   <li>{@code quantity} is the authorized decimal string when a return
 *       quantity was supplied, else {@code null} (claims always carry
 *       {@code "1"});</li>
 *   <li>{@code detail} is the raw claim problem text when supplied, else
 *       {@code null}.</li>
 * </ul>
 *
 * <p>Promotion re-validates from these fields: a fully captured intent is
 * re-resolved by scoped identity ({@code itemSku} inside the freshly verified
 * owned order); a partial intent re-runs the normal validation path from the
 * raw fields and returns the same clarification outcomes
 * ({@code ITEM_REQUIRED} / {@code REASON_REQUIRED} / {@code PROBLEM_REQUIRED} /
 * {@code QUANTITY_REQUIRED}) the model-driven path would have returned.
 */
public record DeferredProcedureIntent(
        ProcedureType type,
        String orderNumber,
        UUID orderId,
        String itemDisplayName,
        String itemSku,
        String reasonName,
        /** Return quantity as an authorized decimal string; {@code null} when the request did not name one. */
        String quantity,
        String detail,
        Instant createdAt) {

    public DeferredProcedureIntent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Builds the deferred form of a request that reached the coordinator's
     * procedure-entry point: the order was resolved, the item (if any) was
     * resolved, and all mutation-relevant fields are already known.
     *
     * @param type            the requested procedure type
     * @param target          the resolved, customer-owned order reference
     * @param collectedData   the same data map that would have seeded a
     *                        {@code ProcedureState}: {@code itemReference}
     *                        (SKU), {@code reason} (parsed enum name),
     *                        {@code description} (raw claim problem text)
     * @param itemDisplayName the customer-visible product name, if item-level
     */
    static DeferredProcedureIntent of(
            ProcedureType type, VerifiedOrderRef target, Map<String, String> collectedData, String itemDisplayName) {
        return new DeferredProcedureIntent(
                type,
                target.orderNumber(),
                target.orderId(),
                itemDisplayName,
                collectedData.get("itemReference"),
                collectedData.get("reason"),
                collectedData.getOrDefault("quantity", ProcedureRequestFingerprint.defaultQuantity()),
                collectedData.get("description"),
                Instant.now());
    }

    /**
     * Canonical identity of this intent, comparable with
     * {@link ProcedureRequestFingerprint#ofRequest} and
     * {@link ProcedureRequestFingerprint#ofActive} so duplicate detection
     * uses one rule everywhere. Pass 2D-B cleanup: the raw free-text
     * {@code detail} is payload carried for the restart, not identity - two
     * wordings of the same claim are the same action.
     *
     * <p>Partial intents (fields still {@code null}) never equal a fully
     * resolved fingerprint, so a partial intent can never be mistaken for
     * the already-active procedure. Two partial intents for genuinely
     * different raw requests are told apart by
     * {@link ProcedureCoordinator}'s deferred-dedupe, which additionally
     * compares the raw customer-supplied selectors.
     */
    public ProcedureRequestFingerprint fingerprint() {
        return new ProcedureRequestFingerprint(type, orderId, itemSku, quantity, reasonName);
    }

    /**
     * Builds the deferred form of a request that was captured while another
     * procedure was active, before every field was known. Whatever was
     * already resolved or supplied is kept; the rest stays {@code null} for
     * promotion-time re-validation. Never carries authorization.
     */
    static DeferredProcedureIntent incomplete(
            ProcedureType type, VerifiedOrderRef target, String itemDisplayName, String itemSku,
            String reasonName, String quantity, String detail) {
        return new DeferredProcedureIntent(
                type,
                target.orderNumber(),
                target.orderId(),
                itemDisplayName,
                itemSku,
                reasonName,
                quantity,
                detail,
                Instant.now());
    }
}
