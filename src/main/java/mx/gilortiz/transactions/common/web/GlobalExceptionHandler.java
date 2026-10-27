package mx.gilortiz.transactions.common.web;

import mx.gilortiz.transactions.transaction.BusinessRuleException;
import mx.gilortiz.transactions.transaction.IdempotencyKeyConflictException;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Traduce excepciones a respuestas RFC 9457 (ProblemDetail).
 * Cada error lleva un código estable en inglés ("code") y un mensaje en español ("detail").
 * <p>
 * Extiende ResponseEntityExceptionHandler para que los errores propios de Spring
 * (404, 405, etc.) también respondan como ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio no cumplida",
                ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Llave de idempotencia reutilizada",
                ex.getMessage(), "IDEMPOTENCY_KEY_REUSED");
    }

    // Último recurso: se registra el detalle en el log, pero nunca se expone al cliente.
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrió un error inesperado; intenta de nuevo más tarde", "INTERNAL_ERROR");
    }

    // @Valid sobre el body.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest(ex, messagesOf(ex.getBindingResult().getAllErrors()), headers, request);
    }

    // Restricciones sobre parámetros (page, limit, Idempotency-Key). Cuando un método
    // tiene restricciones en sus parámetros, Spring también reporta aquí los errores del body.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest(ex, messagesOf(ex.getAllErrors()), headers, request);
    }

    // JSON mal formado o un valor de enum inexistente (p. ej. "type": "PAYMENT").
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest(ex,
                List.of("El cuerpo no es un JSON válido o contiene valores no permitidos (type: CREDIT o DEBIT)"),
                headers, request);
    }

    // Query param con tipo inválido (p. ej. status=APROBADA).
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = "El parámetro %s tiene un valor inválido: %s".formatted(ex.getPropertyName(), ex.getValue());
        return invalidRequest(ex, List.of(message), headers, request);
    }

    private ResponseEntity<Object> invalidRequest(Exception ex, List<String> errors,
                                                  HttpHeaders headers, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "La solicitud no cumple el formato esperado", "INVALID_REQUEST");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static List<String> messagesOf(List<? extends MessageSourceResolvable> errors) {
        return errors.stream().map(MessageSourceResolvable::getDefaultMessage).toList();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
