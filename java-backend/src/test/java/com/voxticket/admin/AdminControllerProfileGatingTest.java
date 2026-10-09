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

@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "voxticket.otp.delivery=email",
        "spring.mail.host=localhost",
        // No active profile on purpose: the context must still satisfy
        // TierChatClientRegistry's fail-fast provider validation, so dummy
        // keys are supplied here. No live provider call ever occurs.
        // Phase 2: pin the rule-only router - with no profile the strategy
        // would default to HYBRID, which fail-fast requires routing model
        // files that tests must never need.
        "voxticket.ai.providers.google.api-key=test-google-key",
        "voxticket.ai.providers.groq.api-key=test-groq-key",
        "voxticket.ai.providers.cerebras.api-key=test-cerebras-key",
        "voxticket.ai.selector.strategy=RULE_ONLY"})
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
