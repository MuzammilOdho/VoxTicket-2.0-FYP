package com.voxticket.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Pure Jakarta Bean Validation test - no Spring context needed. */
class ChatRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void blankMessageFailsValidation() {
        ChatRequest request = new ChatRequest("s1", "+923001234567", "  ");

        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("message"));
    }

    @Test
    void missingCustomerPhoneIsValid() {
        ChatRequest request = new ChatRequest("s1", null, "hello");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void malformedCustomerPhoneFailsValidation() {
        ChatRequest request = new ChatRequest("s1", "0300-1234567", "hello");

        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("customerPhone"));
    }

    @Test
    void wellFormedRequestIsValid() {
        ChatRequest request = new ChatRequest("s1", "+923001234567", "Where is my latest order?");

        assertThat(validator.validate(request)).isEmpty();
    }
}