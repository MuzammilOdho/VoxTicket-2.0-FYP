package com.voxticket.api.voice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P4.1: the trigger customer has not consented to outbound calls (or has
 * no consent row at all - the default is not-consented).
 *
 * <p>Maps to 403 with a stable machine-readable code in the message so
 * API callers can distinguish "fix the consent" from other failures.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class OutboundConsentRequiredException extends RuntimeException {

    public OutboundConsentRequiredException(String customerPhone) {
        super("OUTBOUND_CONSENT_REQUIRED: customer " + customerPhone
                + " has not consented to outbound calls");
    }
}
