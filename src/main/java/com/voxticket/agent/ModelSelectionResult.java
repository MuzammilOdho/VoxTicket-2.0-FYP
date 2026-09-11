package com.voxticket.agent;

/** The tier plus WHY it was chosen - captures "routing reason" for observability without needing a separate lookup. */
public record ModelSelectionResult(ModelTier tier, String reason) {
}