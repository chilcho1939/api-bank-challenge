package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;

/**
 * Datos para ejecutar una transacción, independientes de la capa web.
 * El controller traduce el request HTTP a este comando.
 */
public record ExecuteTransactionCommand(String idempotencyKey,
                                        String accountId,
                                        TransactionType type,
                                        BigDecimal amount,
                                        String currency,
                                        String description) {}