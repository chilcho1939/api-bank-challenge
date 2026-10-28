package mx.gilortiz.transactions.transaction;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Flujo completo sin mocks internos: API → reglas → proveedor (WireMock) → Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Flujo completo: API + Postgres + proveedor simulado")
public class TransactionIntegrationTest {

    private static final String PROVIDER_PATH = "/provider/v1/execute";
    private static final String APPROVED_JSON = """
            {"transactionId":"txn-789","status":"APPROVED","balance":5500.00,"executedAt":"2025-03-15T10:30:00Z"}
            """;
    private static final String REJECTED_JSON = """
            {"status":"REJECTED","code":"INSUFFICIENT_FUNDS","message":"Not enough balance"}
            """;

    // Arranca antes que el contexto de Spring para que su puerto exista al resolver las propiedades.
    private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", wireMock::baseUrl);
        // Timeout corto para que el test de timeout no tarde 3 s.
        registry.add("provider.read-timeout", () -> "500ms");
    }

    @AfterAll
    static void stopProvider() {
        wireMock.stop();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TransactionRepository repository;

    @Autowired
    TransactionService service;

    @Autowired
    CircuitBreaker providerCircuitBreaker;

    // Cada test empieza limpio: BD vacía, sin stubs y con el circuito cerrado.
    @BeforeEach
    void resetState() {
        repository.deleteAll();
        wireMock.resetAll();
        providerCircuitBreaker.reset();
    }

    @Test
    @DisplayName("una transacción aprobada se persiste como EXECUTED con el saldo del proveedor")
    void approvedTransactionIsPersisted() throws Exception {
        givenProviderApprovesAfter(0);

        ResultActions response = postTransaction(body("acc-1", "CREDIT", "1500.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.createdAt").exists());

        Transaction stored = repository.findById(idFrom(response)).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(stored.getProviderTransactionId()).isEqualTo("txn-789");
        assertThat(stored.getBalanceAfter()).isEqualByComparingTo("5500.00");
    }

    @Test
    @DisplayName("un rechazo del proveedor se persiste como REJECTED con su código")
    void rejectedTransactionIsPersisted() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo(PROVIDER_PATH)).willReturn(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody(REJECTED_JSON)));

        ResultActions response = postTransaction(body("acc-1", "DEBIT", "100.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"));

        assertThat(repository.findById(idFrom(response)).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    @DisplayName("si el proveedor no responde a tiempo, la transacción queda UNKNOWN")
    void timeoutIsPersistedAsUnknown() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo(PROVIDER_PATH))
                .willReturn(okJson(APPROVED_JSON).withFixedDelay(1_500)));

        ResultActions response = postTransaction(body("acc-1", "DEBIT", "100.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        assertThat(repository.findById(idFrom(response)).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.UNKNOWN);
    }

    @Test
    @DisplayName("una regla violada responde 422, no llama al proveedor y no persiste nada")
    void ruleViolationNeverReachesProvider() throws Exception {
        postTransaction(body("acc-1", "DEBIT", "20000.00"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"));

        assertThat(repository.count()).isZero();
        wireMock.verify(0, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
    }

    @Test
    @DisplayName("un reintento con la misma Idempotency-Key devuelve la misma transacción")
    void idempotentRetryReturnsSameTransaction() throws Exception {
        givenProviderApprovesAfter(0);
        String request = body("acc-1", "DEBIT", "250.00");

        UUID firstId = idFrom(postTransaction(request, "pago-001").andExpect(status().isCreated()));
        UUID secondId = idFrom(postTransaction(request, "pago-001").andExpect(status().isCreated()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(repository.count()).isEqualTo(1);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
    }

    @Test
    @DisplayName("10 solicitudes simultáneas con la misma llave ejecutan una sola transacción")
    void concurrentRequestsWithSameKeyExecuteOnce() throws Exception {
        // El retraso hace que las solicitudes se traslapen mientras la primera espera al proveedor.
        givenProviderApprovesAfter(200);
        ExecuteTransactionCommand command = new ExecuteTransactionCommand("pago-concurrente", "acc-1",
                TransactionType.DEBIT, new BigDecimal("100.00"), "MXN", "Pago");
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Transaction>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                results.add(executor.submit(() -> {
                    startSignal.await(); // todas arrancan al mismo tiempo
                    return service.execute(command);
                }));
            }
            startSignal.countDown();
        } // close() espera a que terminen todas

        Set<UUID> ids = new HashSet<>();
        for (Future<Transaction> result : results) {
            ids.add(result.get().getId());
        }
        assertThat(ids).hasSize(1);
        assertThat(repository.count()).isEqualTo(1);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
    }

    @Test
    @DisplayName("la consulta filtra por cuenta y estado, y pagina los resultados")
    void searchFiltersAndPaginates() throws Exception {
        givenProviderApprovesAfter(0);
        for (int i = 0; i < 3; i++) {
            postTransaction(body("acc-A", "CREDIT", "100.00"), null).andExpect(status().isCreated());
        }
        postTransaction(body("acc-B", "CREDIT", "100.00"), null).andExpect(status().isCreated());

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-A")
                        .param("status", "EXECUTED")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].accountId").value("acc-A"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("el contrato OpenAPI expone ambos endpoints y el header Idempotency-Key")
    void openApiDescribesEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/transactions'].post").exists())
                .andExpect(jsonPath("$.paths['/transactions'].get").exists())
                .andExpect(jsonPath("$.paths['/transactions'].post.parameters[?(@.name == 'Idempotency-Key')]").exists());
    }

    private ResultActions postTransaction(String body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static UUID idFrom(ResultActions response) throws Exception {
        String json = response.andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static void givenProviderApprovesAfter(int delayMillis) {
        wireMock.stubFor(WireMock.post(urlEqualTo(PROVIDER_PATH))
                .willReturn(okJson(APPROVED_JSON).withFixedDelay(delayMillis)));
    }

    private static String body(String accountId, String type, String amount) {
        return """
                {"accountId":"%s","type":"%s","amount":%s,"currency":"MXN","description":"Prueba"}
                """.formatted(accountId, type, amount);
    }
}
