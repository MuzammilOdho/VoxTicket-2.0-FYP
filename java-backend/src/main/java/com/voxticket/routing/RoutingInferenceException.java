package com.voxticket.routing;

/** Phase 2: unexpected failure of the local routing inference pipeline. Never swallowed. */
public class RoutingInferenceException extends RuntimeException {
    public RoutingInferenceException(String message) {
        super(message);
    }

    public RoutingInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
