package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Guards the LLM-facing contracts of the procedure tools: every tool starts a deterministic
 * server-controlled procedure and the call alone never proves the action succeeded; claims
 * additionally require server-controlled confirmation. Also guards the Spring AI schema
 * contract: only orderReference is required on requestReturn/requestClaim - item, reason and
 * problem are optional so the model calls the tool early instead of interrogating the
 * customer for details first.
 */
class ProcedureRequestToolsDescriptionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String toolDescription(String methodName, Class<?>... params) throws NoSuchMethodException {
        Method method = ProcedureRequestTools.class.getMethod(methodName, params);
        Tool tool = method.getAnnotation(Tool.class);
        assertThat(tool).as("expected @Tool on %s", methodName).isNotNull();
        return tool.description();
    }

    @Test
    void cancellationDescriptionFramesTheToolAsAProcedureStartNotAProof() throws Exception {
        String description = toolDescription("requestCancellation", String.class);

        assertThat(description).contains("deterministic cancellation procedure");
        assertThat(description).contains("procedure state determines what happens next");
        assertThat(description).contains("does not prove cancellation succeeded");
    }

    @Test
    void returnDescriptionInvitesEarlyCallsWithUnknownItemOrReason() throws Exception {
        String description = toolDescription("requestReturn", String.class, String.class, String.class, String.class);

        assertThat(description).contains("deterministic return procedure");
        assertThat(description).contains("Call even when the item or reason is not yet known");
        assertThat(description).contains("does not prove the return succeeded");
    }

    @Test
    void claimDescriptionKeepsServerControlledConfirmationSemantics() throws Exception {
        String description = toolDescription("requestClaim", String.class, String.class, String.class);

        assertThat(description).contains("deterministic claim procedure");
        assertThat(description).contains("not filed until server-controlled confirmation succeeds");
    }

    @Test
    void humanSupportDescriptionStaysConcise() throws Exception {
        String description = toolDescription("requestHumanSupport", String.class);

        assertThat(description).containsIgnoringCase("human support");
        assertThat(description).hasSizeLessThan(200);
    }

    @Test
    void returnToolParamsAreOptionalInTheAnnotationContract() throws Exception {
        Method method = ProcedureRequestTools.class.getMethod("requestReturn", String.class, String.class, String.class, String.class);

        assertThat(method.getParameters()[0].getAnnotation(ToolParam.class).required()).isTrue();
        assertThat(method.getParameters()[1].getAnnotation(ToolParam.class).required())
                .as("itemReference").isFalse();
        assertThat(method.getParameters()[2].getAnnotation(ToolParam.class).required())
                .as("reason").isFalse();
        assertThat(method.getParameters()[3].getAnnotation(ToolParam.class).required())
                .as("quantity").isFalse();
    }

    @Test
    void claimToolParamsAreOptionalInTheAnnotationContract() throws Exception {
        Method method = ProcedureRequestTools.class.getMethod("requestClaim", String.class, String.class, String.class);

        assertThat(method.getParameters()[0].getAnnotation(ToolParam.class).required()).isTrue();
        assertThat(method.getParameters()[1].getAnnotation(ToolParam.class).required())
                .as("itemReference").isFalse();
        assertThat(method.getParameters()[2].getAnnotation(ToolParam.class).required())
                .as("problem").isFalse();
    }

    private List<String> requiredSchemaParams(String toolName) throws Exception {
        var tools = new ProcedureRequestTools(
                mock(ProcedureCoordinator.class), ConversationSession.newSession("schema-test", Channel.CHAT));
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        for (ToolCallback callback : callbacks) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                List<String> required = new ArrayList<>();
                objectMapper.readTree(callback.getToolDefinition().inputSchema())
                        .path("required")
                        .forEach(node -> required.add(node.asText()));
                return required;
            }
        }
        throw new AssertionError("no Spring AI tool callback named " + toolName);
    }

    @Test
    void returnItemAndReasonAreOptionalInTheSpringAiSchema() throws Exception {
        assertThat(requiredSchemaParams("requestReturn")).containsExactly("orderReference");
    }

    @Test
    void claimItemAndProblemAreOptionalInTheSpringAiSchema() throws Exception {
        assertThat(requiredSchemaParams("requestClaim")).containsExactly("orderReference");
    }

    @Test
    void cancellationKeepsOnlyOrderReferenceRequiredInTheSpringAiSchema() throws Exception {
        assertThat(requiredSchemaParams("requestCancellation")).containsExactly("orderReference");
    }
}
