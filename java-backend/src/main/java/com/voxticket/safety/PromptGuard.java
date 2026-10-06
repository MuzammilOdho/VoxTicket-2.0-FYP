package com.voxticket.safety;

/**
 * Spec §45. Defense-in-depth ONLY - the structural security boundary
 * (IDOR-scoped tools, identity assurance checks, no direct-mutation tools)
 * does not depend on this ever firing correctly. A false negative here (an
 * injection attempt this misses) must still be unable to produce an
 * unauthorized business operation, because the tool surface itself makes
 * that impossible regardless of what the model is tricked into "wanting"
 * to do.
 */
public interface PromptGuard {
    PromptGuardVerdict evaluate(String userInput);
}