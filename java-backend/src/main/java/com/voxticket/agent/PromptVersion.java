package com.voxticket.agent;

/**
 * P3.1: identifies the exact system-prompt template a turn ran with.
 *
 * <p>The {@code version} (e.g. {@code "v1"}) selects the template file
 * ({@code prompts/system-v1.txt}); the {@code contentHash} is the SHA-256 of
 * the loaded template bytes, so a trace pins the prompt byte-for-byte even
 * if the file is edited without bumping the version. Both are recorded on
 * every {@link com.voxticket.observability.TurnTrace}.
 *
 * @param version       the active prompt version (from {@code voxticket.prompt.version})
 * @param templateName  the template name (currently always {@code "system"})
 * @param contentHash   lowercase hex SHA-256 of the template content
 */
public record PromptVersion(String version, String templateName, String contentHash) {
}
