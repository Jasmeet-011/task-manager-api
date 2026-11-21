package dev.jasmeet.taskmanager.ai;

/**
 * The model call hit the connect or the read timeout. Mapped to 504 AI_TIMEOUT.
 * The message is for the server log only and never reaches the client.
 */
public class AiTimeoutException extends RuntimeException {

    public AiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
