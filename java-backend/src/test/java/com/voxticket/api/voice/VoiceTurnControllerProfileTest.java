package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * P1.5: voice endpoints exist in every Spring profile (including
 * {@code prod}). Authorization is unchanged: the existing API security
 * chain already covers {@code /api/v1/voice/**}, exactly like
 * {@code /api/v1/chat}.
 */
class VoiceTurnControllerProfileTest {

    @Test
    void voiceEndpointsAreRegisteredInEveryProfile() {
        assertThat(VoiceTurnController.class.getAnnotation(Profile.class))
                .as("VoiceTurnController must not carry a @Profile gate")
                .isNull();
    }
}
