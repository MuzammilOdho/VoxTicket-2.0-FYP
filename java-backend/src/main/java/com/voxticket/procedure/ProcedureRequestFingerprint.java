package com.voxticket.procedure;

import com.voxticket.identity.VerifiedOrderRef;
import java.util.Map;
import java.util.UUID;

/**
 * Pass 2D-B: the deterministic identity of a procedure request.
 *
 * <p>Two requests are the same action if and only if every mutation-relevant
 * field already known is equal:
 * <ul>
 *   <li>CANCELLATION: procedure type + order identity;</li>
 *   <li>RETURN: type + order + item + quantity + parsed return reason;</li>
 *   <li>CLAIM: type + order + item + parsed claim reason.</li>
 * </ul>
 *
 * <p>Pass 2D-B cleanup: the raw free-text claim problem description is
 * deliberately NOT an identity component. "I haven't received it" and "the
 * package never arrived" describe the same intended claim; treating wording
 * as identity would fork duplicate procedures out of paraphrases. The
 * description remains payload - it is stored on the {@code ProcedureState}
 * and on the deferred intent - but it never decides sameness.
 *
 * <p>Removing free text from identity does NOT allow silent mutation of a
 * confirmation-bound claim:
 * <ul>
 *   <li>a changed item, quantity, or parsed reason is a different
 *       fingerprint and therefore a different action (deferred, never
 *       merged into the pending confirmation);</li>
 *   <li>a same-reason rewording while confirmation is pending is
 *       {@code ALREADY_PENDING}: the already-presented confirmation payload
 *       (including the original description) is kept untouched - the new
 *       wording is not silently swapped in. A customer who explicitly wants
 *       different claim details abandons and restarts.</li>
 * </ul>
 *
 * <p>A return/claim for a different item on the same order is therefore never
 * mistaken for the same action, and a changed quantity or reason after an
 * OTP/confirmation was issued never reuses the old authorization.
 *
 * <p>Record equality is structural, so {@code equals} is the whole
 * comparison - there is no fuzzy matching. Filling a previously missing slot
 * (item/reason/problem) <em>before</em> any OTP or confirmation was issued
 * never reaches this comparison with a live procedure, because clarification
 * states create no {@code ProcedureState}; the completed request is simply
 * the continuation the model was asked for.
 */
public record ProcedureRequestFingerprint(
        ProcedureType type,
        UUID orderId,
        String itemSku,
        String quantity,
        String reasonName) {

    /**
     * The quantity every return request authorizes when the model does not
     * name one. The request tool carries an optional quantity parameter; a
     * changed quantity after an OTP was issued is a different fingerprint
     * and never reuses the old authorization.
     */
    static String defaultQuantity() {
        return "1";
    }

    /**
     * Fingerprint of a new request, from the same collected-data shape that
     * seeds a {@link ProcedureState}. The {@code description} entry is
     * intentionally not read: raw customer wording is payload, not identity.
     */
    public static ProcedureRequestFingerprint ofRequest(
            ProcedureType type, VerifiedOrderRef target, Map<String, String> collectedData) {
        return new ProcedureRequestFingerprint(
                type,
                target.orderId(),
                collectedData.get("itemReference"),
                collectedData.getOrDefault("quantity", defaultQuantity()),
                collectedData.get("reason"));
    }

    /** Fingerprint of the currently active procedure, if any. */
    public static ProcedureRequestFingerprint ofActive(ProcedureState procedure) {
        return ofRequest(procedure.getType(), procedure.getVerifiedTarget(), procedure.getCollectedData());
    }
}
