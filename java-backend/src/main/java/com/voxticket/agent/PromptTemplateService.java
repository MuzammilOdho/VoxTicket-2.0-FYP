package com.voxticket.agent;

import com.voxticket.config.PromptProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P3.1: loads the versioned system-prompt template selected by
 * {@code voxticket.prompt.version}.
 *
 * <p>Templates live in {@code src/main/resources/prompts/} as
 * {@code system-{version}.txt} (e.g. version "v1" -&gt; system-v1.txt). The template is read once at startup and
 * its SHA-256 recorded alongside the version on every turn trace, so a
 * trace pins the prompt byte-for-byte. A missing template is a startup
 * failure - running the agent with no (or the wrong) system prompt is never
 * a silent fallback.
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private static final String TEMPLATE_NAME = "system";

    private final PromptVersion current;
    private final String systemPromptBase;

    public PromptTemplateService(PromptProperties properties) {
        String version = properties.version();
        // version is "v1" -> prompts/system-v1.txt; "v2" -> prompts/system-v2.txt.
        String resource = "prompts/system-" + version + ".txt";
        String content = loadTemplate(resource);
        this.systemPromptBase = content;
        this.current = new PromptVersion(version, TEMPLATE_NAME, sha256Hex(content));
        log.info("event=prompt_template_loaded version={} template={} contentHash={} chars={}",
                version, TEMPLATE_NAME, current.contentHash(), content.length());
    }

    /** The loaded system-prompt base text (the old {@code SYSTEM_PROMPT_BASE}). */
    public String systemPromptBase() {
        return systemPromptBase;
    }

    /** The active prompt version identity, recorded on every turn trace. */
    public PromptVersion current() {
        return current;
    }

    private static String loadTemplate(String resource) {
        try (InputStream in = PromptTemplateService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "prompt template not found on classpath: " + resource
                                + " - add prompts/system-v{version}.txt or fix voxticket.prompt.version");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read prompt template: " + resource, e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
