package com.voxticket.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs with no active profile on purpose, to prove ChatController doesn't
 * exist outside dev/test. As of Phase 9, that also means DevOtpDeliveryService
 * (profile-gated) isn't available, so this context needs SOME OtpDeliveryService
 * to boot at all - switched to email delivery here with a placeholder host,
 * since bean construction alone never actually connects to it.
 */
@Testcontainers
@TestPropertySource(properties = {"voxticket.otp.delivery=email", "spring.mail.host=localhost"})
@SpringBootTest
class ChatControllerProfileGatingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void chatControllerDoesNotExistOutsideDevOrTestProfile() {
        assertThat(applicationContext.getBeanNamesForType(ChatController.class)).isEmpty();
    }
}