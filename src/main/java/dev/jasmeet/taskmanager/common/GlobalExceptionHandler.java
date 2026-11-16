package dev.jasmeet.taskmanager.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ClassUtils;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Maps every exception to the ADR-005 body: RFC 9457 ProblemDetail plus "code" and "fieldErrors".
 *
 * <p>It extends ResponseEntityExceptionHandler, which already turns every Spring MVC exception into
 * a ProblemDetail. We override the ones where we can name the failing field, and we override
 * handleExceptionInternal so that any exception we did not list still gets "code" and "fieldErrors".
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ADR-005 shows "type": "about:blank" in every body, so we set it explicitly.
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    // Fallback for 4xx statuses ADR-005 has no specific code for (e.g. 406). Amends ADR-005.
    private static final String CLIENT_ERROR = "CLIENT_ERROR";

    // ---------- our own exceptions ----------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return respond(ex, null, HttpStatus.NOT_FOUND, "Not found", ex.getMessage(),
                NOT_FOUND, List.of(), request);
    }

    // ---------- 400: validation and unreadable input ----------

    /** Bean Validation failures on a request body. Every failing field is listed. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationFailed(ex, headers, violations, request);
    }

    /** Constraint annotations on query params, e.g. @Min and @Max on page and size. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.add(new FieldViolation(name != null ? name : "parameter", error.getDefaultMessage()));
            }
        }
        return validationFailed(ex, headers, violations, request);
    }

    /** Path or query param of the wrong type, e.g. /tasks/abc or ?page=abc. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ex.getPropertyName();
        FieldViolation violation = new FieldViolation(
                field != null ? field : "parameter", invalidValueMessage(ex.getRequiredType()));
        return validationFailed(ex, headers, List.of(violation), request);
    }

    /**
     * The body could not be turned into the request record. If Jackson tells us which field was
     * wrong (unknown enum value, malformed date, wrong type) that is VALIDATION_FAILED naming the
     * field. Anything else (broken JSON syntax, empty body) is MALFORMED_REQUEST.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            String message = mismatch instanceof InvalidFormatException
                    ? invalidValueMessage(mismatch.getTargetType())
                    : "has an invalid type";
            return validationFailed(ex, headers,
                    List.of(new FieldViolation(field.isEmpty() ? "body" : field, message)), request);
        }
        return respond(ex, headers, HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body is missing or is not valid JSON.",
                MALFORMED_REQUEST, List.of(), request);
    }

    // ---------- 404, 405, 415 ----------

    /** Unknown route. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ex, headers, HttpStatus.NOT_FOUND, "Not found",
                "The requested resource was not found.", NOT_FOUND, List.of(), request);
    }

    /** Spring's headers are passed through, so the Allow header survives. */
    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ex, headers, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                "This HTTP method is not supported for this resource.",
                METHOD_NOT_ALLOWED, List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ex, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                "The request Content-Type is not supported. Use application/json.",
                UNSUPPORTED_MEDIA_TYPE, List.of(), request);
    }

    // ---------- 500: everything else ----------

    /**
     * Anything unexpected. The full stack trace goes to the log and never to the client, and
     * ex.getMessage() is not returned either because it can contain SQL or internal details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            log.error("Unhandled exception on {} {}", servletRequest.getHttpMethod(),
                    servletRequest.getRequest().getRequestURI(), ex);
        } else {
            log.error("Unhandled exception", ex);
        }
        return respond(ex, null, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred.", INTERNAL_ERROR, List.of(), request);
    }

    // ---------- safety net ----------

    /**
     * Every Spring MVC exception we did not override above ends up here. Bodies that already have
     * "code" and "fieldErrors" (all the handlers above) pass through untouched, and the headers Spring
     * set are kept. We only fill in what is missing.
     *
     * <p>super can return null when the response is already committed. An error handler must never
     * throw, so a null response is returned as is.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            // Spring leaves "type" out of the JSON unless it is set explicitly, but ADR-005 shows it.
            if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
                problem.setType(ABOUT_BLANK);
            }
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", defaultCode(statusCode));
            }
            if (problem.getProperties() == null || !problem.getProperties().containsKey("fieldErrors")) {
                problem.setProperty("fieldErrors", List.of());
            }
            if (problem.getInstance() == null) {
                problem.setInstance(instanceOf(request));
            }
        }
        return response;
    }

    // ---------- helpers ----------

    private ResponseEntity<Object> validationFailed(Exception ex, HttpHeaders headers,
            List<FieldViolation> violations, WebRequest request) {
        return respond(ex, headers, HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", VALIDATION_FAILED, violations, request);
    }

    private ResponseEntity<Object> respond(Exception ex, HttpHeaders headers, HttpStatus status,
            String title, String detail, String code, List<FieldViolation> fieldErrors, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ABOUT_BLANK);
        problem.setTitle(title);
        problem.setInstance(instanceOf(request));
        problem.setProperty("code", code);
        problem.setProperty("fieldErrors", fieldErrors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static URI instanceOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            try {
                return URI.create(servletRequest.getRequest().getRequestURI());
            } catch (IllegalArgumentException e) {
                return null; // never let building the error body fail
            }
        }
        return null;
    }

    private static String defaultCode(HttpStatusCode status) {
        int value = status.value();
        if (value == 400) return VALIDATION_FAILED;
        if (value == 404) return NOT_FOUND;
        if (value == 405) return METHOD_NOT_ALLOWED;
        if (value == 415) return UNSUPPORTED_MEDIA_TYPE;
        if (status.is5xxServerError()) return INTERNAL_ERROR;
        return CLIENT_ERROR;
    }

    /** Human message for "this value cannot be turned into that type", shared by body and param errors. */
    private static String invalidValueMessage(Class<?> target) {
        if (target != null && target.isEnum()) {
            return "must be one of " + Arrays.stream(target.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", "));
        }
        if (target != null && LocalDate.class.isAssignableFrom(target)) {
            return "must be a valid date in yyyy-MM-dd format";
        }
        if (target != null && Number.class.isAssignableFrom(ClassUtils.resolvePrimitiveIfNecessary(target))) {
            return "must be a valid number";
        }
        return "has an invalid value";
    }
}
