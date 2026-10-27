package mx.gilortiz.transactions.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Servicio de ejecución de transacciones")
class TransactionServiceTest {

    @Mock
    TransactionProvider provider;

    @Mock
    TransactionRepository repository;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(new TransactionRules(), provider, repository);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("providerOutcomes")
    @DisplayName("el resultado del proveedor se refleja en la transacción guardada")
    void persistsProviderOutcome(ProviderResult result, TransactionStatus expectedStatus) {
        givenSaveReturnsArgument();
        when(provider.execute(any())).thenReturn(result);

        Transaction transaction = service.execute(command(null, "150.00"));

        assertThat(transaction.getStatus()).isEqualTo(expectedStatus);
        verify(repository, times(2)).save(transaction);
    }

    static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                Arguments.of(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00")), TransactionStatus.EXECUTED),
                Arguments.of(new ProviderResult.Rejected("INSUFFICIENT_FUNDS", "Not enough balance"), TransactionStatus.REJECTED),
                Arguments.of(new ProviderResult.Failed("PROVIDER_UNREACHABLE", "Sin conexión"), TransactionStatus.FAILED),
                Arguments.of(new ProviderResult.Unknown("PROVIDER_NO_RESPONSE", "Sin respuesta"), TransactionStatus.UNKNOWN));
    }

    @Test
    @DisplayName("si una regla no se cumple, no se llama al proveedor ni se guarda nada")
    void ruleViolationStopsBeforeProvider() {
        assertThatThrownBy(() -> service.execute(command(null, "20000.00")))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(provider, repository);
    }

    @Test
    @DisplayName("un reintento con la misma llave devuelve la transacción original")
    void replaysExistingTransaction() {
        Transaction existing = executedTransaction("key-1", "150.00");
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        // Mismo monto con otra escala: 150.0 y 150.00 son la misma solicitud.
        Transaction result = service.execute(command("key-1", "150.0"));

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(provider);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("la misma llave con datos distintos es un conflicto")
    void rejectsKeyReuseWithDifferentPayload() {
        Transaction existing = executedTransaction("key-1", "150.00");
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(command("key-1", "999.00")))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("si dos solicitudes con la misma llave compiten, se devuelve la que ganó")
    void concurrentDuplicateReturnsWinner() {
        Transaction winner = executedTransaction("key-1", "150.00");
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.save(any(Transaction.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Transaction result = service.execute(command("key-1", "150.00"));

        assertThat(result).isSameAs(winner);
        verifyNoInteractions(provider);
    }

    private void givenSaveReturnsArgument() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ExecuteTransactionCommand command(String idempotencyKey, String amount) {
        return new ExecuteTransactionCommand(idempotencyKey, "acc-123456", TransactionType.DEBIT,
                new BigDecimal(amount), "MXN", "Pago");
    }

    private static Transaction executedTransaction(String idempotencyKey, String amount) {
        Transaction transaction = Transaction.pending(idempotencyKey, "acc-123456", TransactionType.DEBIT,
                new BigDecimal(amount), "MXN", "Pago");
        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));
        return transaction;
    }
}
