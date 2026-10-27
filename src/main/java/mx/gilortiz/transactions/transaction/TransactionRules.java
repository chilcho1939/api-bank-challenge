package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;

public class TransactionRules {

    static final BigDecimal MINIMUM_AMOUNT = new BigDecimal("1.00");
    static final BigDecimal MAX_DEBIT_AMOUNT = new BigDecimal("10000.00");
    static final String SUPPORTED_CURRENCY = "MXN";

    public void validate(TransactionType type, BigDecimal amount, String currency) {
        requireSupportedCurrency(currency);
        requireAboveMinimum(amount);
        requireWithinDebitLimit(type, amount);
    }

    private void requireSupportedCurrency(String currency) {
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw new BusinessRuleException("UNSUPPORTED_CURRENCY",
                    "Moneda no soportada: %s. Solo se aceptan transacciones en MXN".formatted(currency));
        }
    }

    // compareTo y no equals: BigDecimal.equals considera la escala (1.0 no es igual a 1.00).
    private void requireAboveMinimum(BigDecimal amount) {
        if (amount.compareTo(MINIMUM_AMOUNT) <= 0) {
            throw new BusinessRuleException("AMOUNT_BELOW_MINIMUM",
                    "El monto debe ser mayor a 1.00");
        }
    }

    private void requireWithinDebitLimit(TransactionType type, BigDecimal amount) {
        if (type == TransactionType.DEBIT && amount.compareTo(MAX_DEBIT_AMOUNT) > 0) {
            throw new BusinessRuleException("DEBIT_LIMIT_EXCEEDED",
                    "Las transacciones DEBIT no pueden exceder 10,000.00 por operación");
        }
    }
}
