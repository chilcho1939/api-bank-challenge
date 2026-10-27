package mx.gilortiz.transactions.provider.http;

import mx.gilortiz.transactions.transaction.Transaction;

import java.math.BigDecimal;

/** Cuerpo de POST /provider/v1/execute, según el contrato del proveedor. */
public record ProviderRequest(String accountId, String type, BigDecimal amount, String currency) {
    static ProviderRequest from(Transaction transaction) {
        return new ProviderRequest(
                transaction.getAccountId(),
                transaction.getType().name(),
                transaction.getAmount(),
                transaction.getCurrency());
    }
}
