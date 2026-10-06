package com.voxticket.agent;

/**
 * Which tool surface a {@link SupportAgent} call may use.
 *
 * <p>{@code FULL} is the normal mode with the complete customer read, policy,
 * and procedure-request tool surface. {@code GUARDED} (Pass 2D-B) is used
 * while one procedure is active: it additionally registers the safe
 * procedure-control tools (abandon active, discard deferred) so the model can
 * understand follow-up mutation requests, corrections, and replacements. The
 * coordinator itself guarantees that a repeated identical request reuses the
 * live procedure and a different request is deferred - a second live
 * procedure is impossible regardless of what the model attempts. Enforcement
 * is actual tool registration, not prompting.
 *
 * <p>{@code READ_ONLY} exposes only the customer read / policy tools. It is
 * kept for residual text and other turns where even procedure-request tools
 * must stay out of reach.
 */
public enum ToolAccessMode {
    FULL,
    GUARDED,
    READ_ONLY
}
