package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Guards the LLM-facing contracts of the procedure tools: cancellation and
 * return are OTP-gated and execute immediately after successful verification
 * (no extra yes/no confirmation), while claims keep explicit confirmation.
 */
class ProcedureRequestToolsDescriptionTest {

    private static String toolDescription(String methodName, Class<?>... params) throws NoSuchMethodException {
        Method method = ProcedureRequestTools.class.getMethod(methodName, params);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool).as("expected @Tool on %s", methodName).isNotNull();
        return tool.description();
    }

    @Test
    void cancellationDescriptionDescribesOtpGatedImmediateExecution() throws Exception {
        String description = toolDescription("requestCancellation", String.class);

        assertThat(description).contains("verification code");
        assertThat(description).containsIgnoringCase("executes immediately");
        assertThat(description).doesNotContain("explicitly confirm");
    }

    @Test
    void returnDescriptionDescribesOtpGatedImmediateExecution() throws Exception {
        String description = toolDescription("requestReturn", String.class, String.class, String.class);

        assertThat(description).contains("verification code");
        assertThat(description).containsIgnoringCase("executes immediately");
        assertThat(description).doesNotContain("explicitly confirm");
    }

    @Test
    void claimDescriptionKeepsExplicitConfirmationSemantics() throws Exception {
        String description = toolDescription("reportOrderProblem", String.class, String.class, String.class);

        assertThat(description).contains("explicitly confirm");
        assertThat(description).doesNotContain("verification code");
    }
}
