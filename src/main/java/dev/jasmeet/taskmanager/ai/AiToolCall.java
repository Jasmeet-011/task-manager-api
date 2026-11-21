package dev.jasmeet.taskmanager.ai;

import tools.jackson.databind.JsonNode;

/**
 * One request to the model: what to tell it, what the user wrote, and the one tool it should call.
 *
 * @param systemPrompt    instructions from the server, trusted
 * @param userText        the user's text, sent as-is, untrusted
 * @param toolName        the tool the model must call
 * @param toolDescription what the tool is for, shown to the model
 * @param inputSchema     JSON Schema for the tool's arguments
 */
public record AiToolCall(
        String systemPrompt,
        String userText,
        String toolName,
        String toolDescription,
        JsonNode inputSchema) {
}
