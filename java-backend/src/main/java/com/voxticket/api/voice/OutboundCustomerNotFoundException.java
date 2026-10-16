package com.voxticket.api.voice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P4.1: no customer row exists for the trigger phone number. Consent is
 * tracked per customer, so an unknown phone cannot be checked - maps to
 * 404, not 403, so callers do not confuse "unknown customer" with "no
 * consent".
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class OutboundCustomerNotFoundException extends RuntimeException {

    public OutboundCustomerNotFoundException(String customerPhone) {
        super("OUTBOUND_CUSTOMER_NOT_FOUND: no customer with phone " + customerPhone);
    }
}
