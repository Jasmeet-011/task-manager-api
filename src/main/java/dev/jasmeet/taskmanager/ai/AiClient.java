package dev.jasmeet.taskmanager.ai;

import tools.jackson.databind.JsonNode;

/**
 * Asks a model to call one tool and returns the arguments it called it with. Nothing here is
 * specific to one provider, so a different provider could implement it (ADR-004).
 *
 * <p>The caller owns the prompt and the tool schema. The implementation owns the HTTP call and the
 * provider's response envelope. It checks that exactly one call to the named tool came back, but it
 * does not check the arguments: that is the caller's job.
 */
public interface AiClient {

    /**
     * @return the tool call's arguments, as the model sent them
     * @throws AiNotConfiguredException  no API key is set. No request is sent.
     * @throws AiTimeoutException        the connect or the read timeout passed
     * @throws AiUpstreamException       the service returned an error status or could not be reached
     * @throws AiOutputInvalidException  the service answered, but not with exactly one call to the tool
     */
    JsonNode callTool(AiToolCall call);
}
