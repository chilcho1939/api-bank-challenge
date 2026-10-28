package mx.gilortiz.transactions.provider.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.Transaction;
import mx.gilortiz.transactions.transaction.TransactionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cliente HTTP del proveedor")
public class HttpTransactionProviderTest {

    private static final String PATH = "/provider/v1/execute";
    private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    private final ProviderClientConfig config = new ProviderClientConfig();

    @BeforeAll
    static void startServer() {
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("200 APPROVED se traduce a Approved y envía el contrato correcto")
    void approved() {
        wireMock.stubFor(post(PATH).willReturn(okJson("""
                {"transactionId":"txn-789","status":"APPROVED","balance":5500.00,"executedAt":"2025-03-15T10:30:00Z"}
                """)));

        ProviderResult result = provider().execute(debit());

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Approved.class, approved -> {
            assertThat(approved.providerTransactionId()).isEqualTo("txn-789");
            assertThat(approved.balance()).isEqualByComparingTo("5500.00");
        });
        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"accountId":"acc-123456","type":"DEBIT","amount":150.00,"currency":"MXN"}
                """)));
    }

    @Test
    @DisplayName("422 REJECTED se traduce a Rejected con el código del proveedor")
    void rejectedWith4xx() {
        wireMock.stubFor(post(PATH).willReturn(rejection(422)));

        ProviderResult result = provider().execute(debit());

        assertThat(result).isEqualTo(new ProviderResult.Rejected("INSUFFICIENT_FUNDS", "Not enough balance"));
    }

    @Test
    @DisplayName("un 5xx con cuerpo REJECTED también es un rechazo")
    void rejectedWith5xx() {
        wireMock.stubFor(post(PATH).willReturn(rejection(500)));

        ProviderResult result = provider().execute(debit());

        assertThat(result).isInstanceOf(ProviderResult.Rejected.class);
    }

    @Test
    @DisplayName("un 503 sin cuerpo queda UNKNOWN y no se reintenta")
    void serverErrorWithoutBodyIsUnknown() {
        wireMock.stubFor(post(PATH).willReturn(serviceUnavailable()));

        ProviderResult result = provider().execute(debit());

        assertThat(result).isInstanceOf(ProviderResult.Unknown.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("un timeout queda UNKNOWN y no se reintenta")
    void timeoutIsUnknown() {
        wireMock.stubFor(post(PATH).willReturn(okJson("{}").withFixedDelay(1_500)));

        ProviderResult result = provider().execute(debit());

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class,
                unknown -> assertThat(unknown.code()).isEqualTo("PROVIDER_NO_RESPONSE"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("si no hay conexión, la transacción seguro no se ejecutó: FAILED")
    void connectionRefusedIsFailed() throws IOException {
        URI closedPort = URI.create("http://localhost:" + freePort());

        ProviderResult result = provider(closedPort, config.providerCircuitBreaker()).execute(debit());

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class,
                failed -> assertThat(failed.code()).isEqualTo("PROVIDER_UNREACHABLE"));
    }

    @Test
    @DisplayName("sin conexión se reintenta 3 veces antes de quedar FAILED")
    void connectionRefusedIsRetried() throws IOException {
        CircuitBreaker circuitBreaker = config.providerCircuitBreaker();

        provider(URI.create("http://localhost:" + freePort()), circuitBreaker).execute(debit());

        // El retry envuelve al circuit breaker: cada intento queda registrado como falla.
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("con el circuito abierto no se llama al proveedor")
    void openCircuitSkipsCall() {
        CircuitBreaker circuitBreaker = config.providerCircuitBreaker();
        circuitBreaker.transitionToOpenState();

        ProviderResult result = provider(URI.create(wireMock.baseUrl()), circuitBreaker).execute(debit());

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class,
                failed -> assertThat(failed.code()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("los rechazos de negocio no abren el circuito")
    void rejectionsKeepCircuitClosed() {
        wireMock.stubFor(post(PATH).willReturn(rejection(422)));
        CircuitBreaker circuitBreaker = config.providerCircuitBreaker();
        HttpTransactionProvider provider = provider(URI.create(wireMock.baseUrl()), circuitBreaker);

        for (int i = 0; i < 5; i++) {
            provider.execute(debit());
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("las fallas técnicas repetidas sí abren el circuito")
    void serverErrorsOpenCircuit() {
        wireMock.stubFor(post(PATH).willReturn(serviceUnavailable()));
        CircuitBreaker circuitBreaker = config.providerCircuitBreaker();
        HttpTransactionProvider provider = provider(URI.create(wireMock.baseUrl()), circuitBreaker);

        for (int i = 0; i < 5; i++) {
            provider.execute(debit());
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private HttpTransactionProvider provider() {
        return provider(URI.create(wireMock.baseUrl()), config.providerCircuitBreaker());
    }

    private HttpTransactionProvider provider(URI baseUrl, CircuitBreaker circuitBreaker) {
        ProviderProperties properties = new ProviderProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(500));
        return new HttpTransactionProvider(config.providerRestClient(properties), circuitBreaker, config.providerRetry());
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder rejection(int status) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":"REJECTED","code":"INSUFFICIENT_FUNDS","message":"Not enough balance"}
                        """);
    }

    private static Transaction debit() {
        return Transaction.pending(null, "acc-123456", TransactionType.DEBIT,
                new BigDecimal("150.00"), "MXN", "Pago");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
