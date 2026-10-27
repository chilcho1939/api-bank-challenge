package mx.gilortiz.transactions.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@DisplayName("API de transacciones")
class TransactionControllerTest {

    private static final String VALID_BODY = """
            {"accountId":"acc-123456","type":"DEBIT","amount":150.00,"currency":"MXN","description":"Pago"}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TransactionService service;

    @Test
    @DisplayName("POST válido responde 201 con la transacción y su estado")
    void createsTransaction() throws Exception {
        when(service.execute(any())).thenReturn(executedTransaction());

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.providerTransactionId").value("txn-789"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00));
    }

    @Test
    @DisplayName("un body sin campos obligatorios responde 400 y no llega al servicio")
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("un type inexistente responde 400")
    void rejectsUnknownType() throws Exception {
        String body = VALID_BODY.replace("DEBIT", "PAYMENT");

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("una regla de negocio violada responde 422 con su código")
    void mapsBusinessRuleTo422() throws Exception {
        when(service.execute(any())).thenThrow(
                new BusinessRuleException("DEBIT_LIMIT_EXCEEDED", "Límite excedido"));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("una llave de idempotencia reutilizada con otros datos responde 409")
    void mapsIdempotencyConflictTo409() throws Exception {
        when(service.execute(any())).thenThrow(new IdempotencyKeyConflictException("key-1"));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("GET devuelve una página con filtros por defecto")
    void searchesWithDefaults() throws Exception {
        when(service.search(any(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(executedTransaction()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/transactions").param("accountId", "acc-123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].accountId").value("acc-123456"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    @DisplayName("un limit mayor a 100 responde 400")
    void rejectsLimitAboveMaximum() throws Exception {
        mockMvc.perform(get("/transactions").param("limit", "500"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("un status inexistente en el filtro responde 400")
    void rejectsUnknownStatusFilter() throws Exception {
        mockMvc.perform(get("/transactions").param("status", "APROBADA"))
                .andExpect(status().isBadRequest());
    }

    private static Transaction executedTransaction() {
        Transaction transaction = Transaction.pending(null, "acc-123456", TransactionType.DEBIT,
                new BigDecimal("150.00"), "MXN", "Pago");
        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));
        return transaction;
    }
}
