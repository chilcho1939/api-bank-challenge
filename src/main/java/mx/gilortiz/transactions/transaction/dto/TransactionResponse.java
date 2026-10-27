package mx.gilortiz.transactions.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mx.gilortiz.transactions.transaction.Transaction;
import mx.gilortiz.transactions.transaction.TransactionStatus;
import mx.gilortiz.transactions.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Representación pública de una transacción. Los campos nulos se omiten. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(UUID id,
                                  String accountId,
                                  TransactionType type,
                                  BigDecimal amount,
                                  String currency,
                                  String description,
                                  TransactionStatus status,
                                  String providerTransactionId,
                                  BigDecimal balanceAfter,
                                  String failureCode,
                                  String failureMessage,
                                  Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getStatus(),
                transaction.getProviderTransactionId(),
                transaction.getBalanceAfter(),
                transaction.getFailureCode(),
                transaction.getFailureMessage(),
                transaction.getCreatedAt());
    }
}
