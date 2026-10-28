package mx.gilortiz.transactions.provider.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.Transaction;
import mx.gilortiz.transactions.transaction.TransactionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Supplier;

/**
 * Implementación HTTP del proveedor externo. Traduce cada respuesta o falla
 * a un {@link ProviderResult}; fuera de este paquete nadie sabe que existe HTTP.
 */
@Component
public class HttpTransactionProvider implements TransactionProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTransactionProvider.class);

    private static final String EXECUTE_PATH = "/provider/v1/execute";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public HttpTransactionProvider(RestClient providerRestClient,
                                   CircuitBreaker providerCircuitBreaker,
                                   Retry providerRetry) {
        this.restClient = providerRestClient;
        this.circuitBreaker = providerCircuitBreaker;
        this.retry = providerRetry;
    }

    @Override
    public ProviderResult execute(Transaction transaction) {
        // El retry envuelve al circuit breaker: cada intento cuenta en las métricas del circuito. Call es la llamada real.
        Supplier<ProviderResult> guardedCall = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> call(transaction)));
        try {
            return guardedCall.get();//Dispara la cadena
        } catch (CallNotPermittedException e) {
            log.warn("Circuito abierto: la transacción {} no se envió al proveedor", transaction.getId());
            return new ProviderResult.Failed("PROVIDER_CIRCUIT_OPEN",
                    "El proveedor no está disponible temporalmente");
        } catch (UnexpectedProviderResponseException e) {
            log.error("Respuesta inesperada del proveedor (HTTP {}) para la transacción {}",
                    e.status, transaction.getId());
            return new ProviderResult.Unknown("PROVIDER_UNEXPECTED_RESPONSE",
                    "El proveedor respondió HTTP %d sin un resultado válido".formatted(e.status));
        } catch (ResourceAccessException e) {
            return classifyIoFailure(transaction, e);
        }
    }

    private ProviderResult call(Transaction transaction) {
        return restClient.post()
                .uri(EXECUTE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProviderRequest.from(transaction))
                .exchange((request, response) -> toResult(response));
    }

    private ProviderResult toResult(ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        ProviderResponse body = readBody(response);

        if (status.is2xxSuccessful() && body != null && APPROVED.equals(body.status())) {
            return new ProviderResult.Approved(body.transactionId(), body.balance());
        }
        // El contrato admite rechazos con 4xx o 5xx: decide el cuerpo, no el código HTTP.
        if (body != null && REJECTED.equals(body.status())) {
            return new ProviderResult.Rejected(body.code(), body.message());
        }
        // Un 4xx sin detalle: el proveedor rechazó la solicitud y no la ejecutó.
        if (status.is4xxClientError()) {
            return new ProviderResult.Rejected("PROVIDER_HTTP_" + status.value(),
                    "El proveedor rechazó la solicitud sin detalle");
        }
        // 5xx sin cuerpo válido o 2xx inesperado: no sabemos qué pasó.
        throw new UnexpectedProviderResponseException(status.value());
    }

    private static ProviderResponse readBody(ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ProviderResponse.class);
        } catch (RestClientException e) {
            // Cuerpo vacío o que no es JSON (p. ej. HTML de un balanceador).
            return null;
        }
    }

    private ProviderResult classifyIoFailure(Transaction transaction, ResourceAccessException e) {
        if (isConnectionFailure(e)) {
            log.warn("No se pudo conectar con el proveedor para la transacción {}", transaction.getId());
            return new ProviderResult.Failed("PROVIDER_UNREACHABLE",
                    "No se pudo establecer conexión con el proveedor");
        }
        // La solicitud pudo haber llegado: no sabemos si el proveedor la ejecutó.
        log.error("Sin respuesta del proveedor para la transacción {}: {}",
                transaction.getId(), e.getMessage());
        return new ProviderResult.Unknown("PROVIDER_NO_RESPONSE",
                "El proveedor no respondió a tiempo; el resultado es incierto");
    }

    // Solo una falla al conectar garantiza que la solicitud nunca llegó al proveedor,
    // por eso es la única falla segura de reintentar.
    static boolean isConnectionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static final class UnexpectedProviderResponseException extends RuntimeException {

        private final int status;

        private UnexpectedProviderResponseException(int status) {
            super("Respuesta inesperada del proveedor: HTTP " + status);
            this.status = status;
        }
    }
}
