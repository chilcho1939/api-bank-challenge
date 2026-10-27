package mx.gilortiz.transactions.transaction;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body de POST /transactions. Aquí solo se valida el formato;
 * las reglas de negocio (mínimo, límite DEBIT, moneda) viven en TransactionRules.
 */
public record CreateTransactionRequest(
        @NotBlank(message = "accountId es obligatorio")
        @Size(max = 64, message = "accountId no puede exceder 64 caracteres")
        String accountId,

        @NotNull(message = "type es obligatorio (CREDIT o DEBIT)")
        TransactionType type,

        // Máximo 2 decimales: NUMERIC(19,2) redondearía en silencio un monto como 10.123.
        @NotNull(message = "amount es obligatorio")
        @Digits(integer = 17, fraction = 2, message = "amount admite máximo 2 decimales")
        BigDecimal amount,

        @NotBlank(message = "currency es obligatorio")
        String currency,

        @Size(max = 255, message = "description no puede exceder 255 caracteres")
        String description) {

    public ExecuteTransactionCommand toCommand(String idempotencyKey) {
        return new ExecuteTransactionCommand(idempotencyKey, accountId, type, amount, currency, description);
    }
}
