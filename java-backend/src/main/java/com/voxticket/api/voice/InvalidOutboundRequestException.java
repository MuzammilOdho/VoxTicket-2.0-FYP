package com.voxticket.api.voice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P4.1: the trigger body failed validation that Bean Validation does not
 * cover (malformed E.164 when the regex is bypassed, blank idempotency
 * key that is present-but-empty). Maps to 400.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidOutboundRequestException extends RuntimeException {

    public InvalidOutboundRequestException(String message) {
        super("INVALID_OUTBOUND_REQUEST: " + message);
    }
}
