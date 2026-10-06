package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * OTP-in-logs is a dev-profile-only convenience. Under any other profile
 * (notably test) the delivery log line must carry only non-secret metadata -
 * the OTP value itself must never be written to logs. The controlled test
 * mechanism (devOtp in VerificationOutcome metadata) lives in
 * VerificationService and is unaffected.
 */
class DevOtpDeliveryServiceTest {

    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(DevOtpDeliveryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private Environment environmentWithDevActive(boolean devActive) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(devActive);
        return environment;
    }

    @Test
    void deliverLogsTheOtpWhenDevProfileIsActive() {
        DevOtpDeliveryService service = new DevOtpDeliveryService(environmentWithDevActive(true));

        List<ILoggingEvent> events = captureLogs(() ->
                service.deliver(new Customer("Ayesha", "Khan", "ayesha@example.com", "+923001234567"),
                        "482913", VerificationPurpose.CANCELLATION));

        assertThat(events).isNotEmpty();
        assertThat(events)
                .anyMatch(event -> event.getFormattedMessage().contains("event=dev_otp_delivery")
                        && event.getFormattedMessage().contains("482913"));
    }

    @Test
    void deliverNeverLogsThePlaintextOtpWhenDevProfileIsNotActive() {
        DevOtpDeliveryService service = new DevOtpDeliveryService(environmentWithDevActive(false));

        List<ILoggingEvent> events = captureLogs(() ->
                service.deliver(new Customer("Ayesha", "Khan", "ayesha@example.com", "+923001234567"),
                        "482913", VerificationPurpose.CANCELLATION));

        assertThat(events).isNotEmpty();
        assertThat(events).noneMatch(event -> event.getFormattedMessage().contains("482913"));
        assertThat(events).anyMatch(event -> event.getFormattedMessage().contains("event=dev_otp_delivery"));
    }
}
