package com.voxticket.policy;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.procedure.ProcedureType;

public record ActionRequest(ProcedureType type, IdentityAssurance requiredAssurance, boolean alreadyConfirmed) {
}