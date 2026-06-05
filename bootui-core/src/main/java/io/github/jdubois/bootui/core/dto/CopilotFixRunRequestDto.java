package io.github.jdubois.bootui.core.dto;

/**
 * Request body for starting a "Fix it with Copilot" run.
 *
 * <p>The descriptor is the sanitized finding the agent should remediate. Both the descriptor and
 * the request originate from the local browser session and are validated server-side before any
 * agent session is created.
 *
 * @param descriptor the finding to fix
 */
public record CopilotFixRunRequestDto(CopilotFixDescriptorDto descriptor) {}
