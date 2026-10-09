package com.voxticket.verification;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spec §9/resolved decision: preferred over SMS for the FYP - simpler and
 * cheaper than depending on Pakistani SMS delivery. Opt-in via
 * voxticket.otp.delivery=email. NOT verified end-to-end from this
 * environment (no live SMTP access here) - bean construction alone doesn't
 * send anything, so this is safe to enable in config without a real mail
 * server reachable, but actually calling deliver() will need one.
 */
@Component
@ConditionalOnProperty(prefix = "voxticket.otp", name = "delivery", havingValue = "email")
public class EmailOtpDeliveryService implements OtpDeliveryService {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailOtpDeliveryService(JavaMailSender mailSender, @Value("${voxticket.otp.email-from:no-reply@voxticket.example}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public OtpDeliveryChannel channel() {
        return OtpDeliveryChannel.EMAIL;
    }

    @Override
    public void deliver(Customer customer, String plainOtp, VerificationPurpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(customer.getEmail());
        message.setSubject("Your VoxTicket verification code");
        message.setText("Your verification code is " + plainOtp + ". It expires in a few minutes. If you didn't request this, you can ignore this email.");
        mailSender.send(message);
    }
}