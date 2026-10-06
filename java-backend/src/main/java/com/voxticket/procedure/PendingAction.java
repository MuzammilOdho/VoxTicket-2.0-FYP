package com.voxticket.procedure;

import com.voxticket.identity.VerifiedOrderRef;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Spec §20. */
public record PendingAction(
        UUID id, ProcedureType actionType, VerifiedOrderRef verifiedTarget, Map<String, String> parameters,
        Instant createdAt, Instant expiresAt, String idempotencyKey) {
}