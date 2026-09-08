package com.voxticket.procedure;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class ProcedureState {

    private final UUID procedureId = UUID.randomUUID();
    private final ProcedureType type;
    private final VerifiedOrderRef verifiedTarget;
    private final Map<String, String> collectedData;
    private final IdentityAssurance requiredAssurance;
    private ProcedureStatus status;
    private PendingAction pendingAction;
    private String pendingDescription;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public ProcedureState(ProcedureType type, VerifiedOrderRef verifiedTarget, Map<String, String> collectedData, IdentityAssurance requiredAssurance) {
        this.type = type;
        this.verifiedTarget = verifiedTarget;
        this.collectedData = collectedData;
        this.requiredAssurance = requiredAssurance;
        this.status = ProcedureStatus.AWAITING_CONFIRMATION;
    }

    public UUID getProcedureId() {
        return procedureId;
    }

    public ProcedureType getType() {
        return type;
    }

    public VerifiedOrderRef getVerifiedTarget() {
        return verifiedTarget;
    }

    public Map<String, String> getCollectedData() {
        return collectedData;
    }

    public IdentityAssurance getRequiredAssurance() {
        return requiredAssurance;
    }

    public ProcedureStatus getStatus() {
        return status;
    }

    public void setStatus(ProcedureStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public PendingAction getPendingAction() {
        return pendingAction;
    }

    public void setPendingAction(PendingAction pendingAction) {
        this.pendingAction = pendingAction;
    }

    /** The natural-language description of the action, captured once at request time so it can be repeated after verification completes. */
    public String getPendingDescription() {
        return pendingDescription;
    }

    public void setPendingDescription(String pendingDescription) {
        this.pendingDescription = pendingDescription;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}