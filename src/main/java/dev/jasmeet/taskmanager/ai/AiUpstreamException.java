package dev.jasmeet.taskmanager.ai;

/**
 * The AI service returned an error status or could not be reached. Mapped to 502 AI_UPSTREAM_ERROR.
 * The message is for the server log only and never reaches the client.
 *
 * <p>operatorMustAct is true when the cause is on our side and retrying will not help: a rejected
 * key (401, 403), a request the API refuses (400), or a model name it does not know (404). Those
 * are logged at ERROR. Rate limits, outages and connection failures are logged at WARN.
 */
public class AiUpstreamException extends RuntimeException {

    private final boolean operatorMustAct;

    public AiUpstreamException(String message, boolean operatorMustAct, Throwable cause) {
        super(message, cause);
        this.operatorMustAct = operatorMustAct;
    }

    public boolean isOperatorMustAct() {
        return operatorMustAct;
    }
}
