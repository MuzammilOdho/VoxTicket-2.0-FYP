package com.voxticket.admin;

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

@Testcontainers
@TestPropertySource(properties = {
        "voxticket.otp.delivery=email",
        "spring.mail.host=localhost",
        // No active profile on purpose: the context must still satisfy
        // TierChatClientRegistry's fail-fast provider validation, so dummy
        // keys are supplied here. No live provider call ever occurs.
        "voxticket.ai.providers.google.api-key=test-google-key",
        "voxticket.ai.providers.groq.api-key=test-groq-key",
        "voxticket.ai.providers.cerebras.api-key=test-cerebras-key"})
@SpringBootTest
class AdminControllerProfileGatingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void adminControllerDoesNotExistOutsideDevOrTestProfile() {
        assertThat(applicationContext.getBeanNamesForType(AdminController.class)).isEmpty();
    }
}
