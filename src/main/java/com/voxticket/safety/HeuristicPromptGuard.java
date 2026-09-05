package com.voxticket.safety;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HeuristicPromptGuard implements PromptGuard {

    private static final Logger log = LoggerFactory.getLogger(HeuristicPromptGuard.class);

    private static final List<CategorizedPattern> PATTERNS = List.of(
            new CategorizedPattern("INSTRUCTION_OVERRIDE",
                    Pattern.compile("(ignore|disregard|forget) (all |any )?(your |the |previous |prior )*(instructions|rules|guidelines)",
                            Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("ROLE_MANIPULATION",
                    Pattern.compile("(act as|pretend (you|to)|you are now|roleplay as) .*(unrestricted|unfiltered|jailbroken|no rules|without restrictions|dan)",
                            Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("ROLE_MANIPULATION",
                    Pattern.compile("\\b(developer mode|jailbreak|dan mode)\\b", Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("PROMPT_DISCLOSURE",
                    Pattern.compile("(reveal|show|print|repeat|what is|what are) (me )?(your |the )?(system )?(prompt|instructions)\\b",
                            Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("UNAUTHORIZED_DATA_ACCESS",
                    Pattern.compile("(show|list|find|give) (me )?(all|every|any) (customers?|orders?)\\b", Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("UNAUTHORIZED_DATA_ACCESS",
                    Pattern.compile("(bypass|skip|disable) (the |your |otp |identity |verification )*(verification|otp|security|authorization)",
                            Pattern.CASE_INSENSITIVE)),
            new CategorizedPattern("SQL_INJECTION_SIGNATURE",
                    Pattern.compile("(drop table|delete from|select \\* from|union select|execute sql|;\\s*--)", Pattern.CASE_INSENSITIVE)));

    @Override
    public PromptGuardVerdict evaluate(String userInput) {
        long start = System.nanoTime();
        PromptGuardVerdict verdict = doEvaluate(userInput);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("event=prompt_guard implementation=heuristic suspicious={} category={} durationMs={}",
                verdict.suspicious(), verdict.category(), durationMs);
        return verdict;
    }

    private PromptGuardVerdict doEvaluate(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return PromptGuardVerdict.allow();
        }
        for (CategorizedPattern candidate : PATTERNS) {
            if (candidate.pattern().matcher(userInput).find()) {
                return PromptGuardVerdict.flagged(candidate.category(), candidate.pattern().pattern());
            }
        }
        return PromptGuardVerdict.allow();
    }

    private record CategorizedPattern(String category, Pattern pattern) {
    }
}