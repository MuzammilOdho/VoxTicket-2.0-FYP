package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Production OTP delivery behavior: the code is emailed only to the
 * customer's own address, from the configured sender, with no other
 * recipients. No live SMTP is needed - the mail sender is mocked.
 */
class EmailOtpDeliveryServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailOtpDeliveryService service = new EmailOtpDeliveryService(mailSender, "no-reply@voxticket.example");

    @Test
    void deliverSendsTheCodeToTheCustomersOwnEmailAddress() {
        Customer customer = new Customer("Ayesha", "Khan", "ayesha@example.com", "+923001234567");

        service.deliver(customer, "482913", VerificationPurpose.CANCELLATION);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("ayesha@example.com");
        assertThat(sent.getFrom()).isEqualTo("no-reply@voxticket.example");
        assertThat(sent.getSubject()).containsIgnoringCase("verification code");
        assertThat(sent.getText()).contains("482913");
    }

    @Test
    void deliverNeverCopiesAnyoneElseOnTheCode() {
        Customer customer = new Customer("Ayesha", "Khan", "ayesha@example.com", "+923001234567");

        service.deliver(customer, "482913", VerificationPurpose.RETURN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).hasSize(1);
        assertThat(captor.getValue().getCc()).isNull();
        assertThat(captor.getValue().getBcc()).isNull();
    }

    @Test
    void channelIsEmail() {
        assertThat(service.channel()).isEqualTo(OtpDeliveryChannel.EMAIL);
    }
}
