package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationLanguageResolver;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ProcedureControlTools;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureRequestTools;
import com.voxticket.rag.PolicyKnowledgeTools;
import com.voxticket.rag.RagService;
import com.voxticket.service.CustomerOrderQueryService;
import org.junit.jupiter.api.Test;

/**
 * Pass 2D-A Task 22: the read-only guarded agent mode must genuinely not
 * register procedure-request tools - enforcement is tool registration, not
 * prompting.
 *
 * <p>Pass 2D-B: the exact tool registration per {@link ToolAccessMode} is
 * pinned below. GUARDED (one procedure live, or a contested deferred slot)
 * registers the full read / policy / procedure-request surface plus the safe
 * procedure-control tools; the coordinator owns the safety invariant.
 */
class SupportAgentToolModeTest {

    private final SupportAgent supportAgent = new SupportAgent(
            mock(TierChatClientRegistry.class),
            mock(ContextBuilder.class),
            mock(ModelSelector.class),
            mock(CustomerOrderQueryService.class),
            mock(RagService.class),
            mock(ProcedureCoordinator.class),
            mock(TurnMetrics.class),
            mock(ConversationAuditService.class),
            new ConversationLanguageResolver());

    private final ConversationSession session = ConversationSession.newSession("tool-mode", Channel.CHAT);

    @Test
    void readOnlyModeRegistersNoProcedureRequestTools() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.READ_ONLY, session);

        assertThat(tools)
                .isNotEmpty()
                .noneMatch(t -> t instanceof ProcedureRequestTools);
    }

    @Test
    void readOnlyModeKeepsCustomerReadAndPolicyTools() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.READ_ONLY, session);

        assertThat(tools).anyMatch(t -> t instanceof CustomerReadTools);
        assertThat(tools).anyMatch(t -> t instanceof PolicyKnowledgeTools);
    }

    @Test
    void fullModeStillRegistersProcedureRequestTools() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.FULL, session);

        assertThat(tools).anyMatch(t -> t instanceof ProcedureRequestTools);
        assertThat(tools).anyMatch(t -> t instanceof CustomerReadTools);
        assertThat(tools).anyMatch(t -> t instanceof PolicyKnowledgeTools);
    }

    @Test
    void guardedModeRegistersTheExactFourToolGroups() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.GUARDED, session);

        assertThat(tools).hasSize(4);
        assertThat(tools).anyMatch(t -> t instanceof CustomerReadTools);
        assertThat(tools).anyMatch(t -> t instanceof PolicyKnowledgeTools);
        assertThat(tools).anyMatch(t -> t instanceof ProcedureRequestTools);
        assertThat(tools).anyMatch(t -> t instanceof ProcedureControlTools);
    }

    @Test
    void fullModeRegistersExactlyThreeToolGroups() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.FULL, session);

        assertThat(tools).hasSize(3);
        assertThat(tools).anyMatch(t -> t instanceof CustomerReadTools);
        assertThat(tools).anyMatch(t -> t instanceof PolicyKnowledgeTools);
        assertThat(tools).anyMatch(t -> t instanceof ProcedureRequestTools);
        assertThat(tools).noneMatch(t -> t instanceof ProcedureControlTools);
    }

    @Test
    void readOnlyModeRegistersExactlyTwoToolGroups() {
        Object[] tools = supportAgent.toolObjectsForMode(ToolAccessMode.READ_ONLY, session);

        assertThat(tools).hasSize(2);
        assertThat(tools).anyMatch(t -> t instanceof CustomerReadTools);
        assertThat(tools).anyMatch(t -> t instanceof PolicyKnowledgeTools);
        assertThat(tools).noneMatch(t -> t instanceof ProcedureRequestTools);
        assertThat(tools).noneMatch(t -> t instanceof ProcedureControlTools);
    }
}
