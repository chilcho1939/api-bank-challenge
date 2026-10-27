package mx.gilortiz.transactions.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ciclo de vida de una transacción")
public class TransactionTest {

    @Test
    @DisplayName("toda transacción nueva nace en PENDING")
    void startsAsPending() {
        Transaction transaction = newPendingTransaction();

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.getProviderTransactionId()).isNull();
    }

    @Test
    @DisplayName("al ejecutarse guarda el id y el saldo del proveedor")
    void markExecutedStoresProviderData() {
        Transaction transaction = newPendingTransaction();

        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(transaction.getProviderTransactionId()).isEqualTo("txn-789");
        assertThat(transaction.getBalanceAfter()).isEqualByComparingTo("5500.00");
    }

    @Test
    @DisplayName("al rechazarse guarda el código y mensaje del proveedor")
    void markRejectedStoresReason() {
        Transaction transaction = newPendingTransaction();

        transaction.markRejected("INSUFFICIENT_FUNDS", "Not enough balance");

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(transaction.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("un estado final no se puede sobrescribir")
    void finalStateCannotChange() {
        Transaction transaction = newPendingTransaction();
        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));

        assertThatThrownBy(() -> transaction.markRejected("ANY", "any"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
    }

    private Transaction newPendingTransaction() {
        return Transaction.pending("key-1", "acc-123456", TransactionType.CREDIT,
                new BigDecimal("1500.00"), "MXN", "Transferencia recibida");
    }
}
