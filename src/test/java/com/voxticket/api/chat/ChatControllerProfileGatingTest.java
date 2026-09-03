package com.voxticket.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the resolved chat-security decision: the dev/test chat surface
 * must not exist outside dev/test profiles. Deliberately does NOT activate
 * either profile.
 */
@Testcontainers
@SpringBootTest
class ChatControllerProfileGatingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void chatControllerDoesNotExistOutsideDevOrTestProfile() {
        assertThat(applicationContext.getBeanNamesForType(ChatController.class)).isEmpty();
    }
}