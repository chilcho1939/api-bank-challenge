package mx.gilortiz.transactions.transaction;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Reglas de negocio de transacciones")
public class TransactionRulesTest {

    private final TransactionRules rules = new TransactionRules();

    @ParameterizedTest(name = "{0} por {1} MXN es válida")
    @CsvSource({
            "CREDIT, 1.01",
            "DEBIT, 1.01",
            "DEBIT, 10000.00",
            "CREDIT, 10000.01",
            "CREDIT, 5000000.00"
    })
    void acceptsValidTransactions(TransactionType type, BigDecimal amount) {
        assertThatCode(() -> rules.validate(type, amount, "MXN"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "un monto de {0} se rechaza")
    @ValueSource(strings = {"1.00", "1", "1.0", "0.99", "0", "-100.00"})
    void rejectsAmountsNotAboveMinimum(BigDecimal amount) {
        assertViolation(TransactionType.CREDIT, amount, "MXN", "AMOUNT_BELOW_MINIMUM");
    }

    @Test
    @DisplayName("un DEBIT de 10,000.01 excede el límite")
    void rejectsDebitAboveLimit() {
        assertViolation(TransactionType.DEBIT, new BigDecimal("10000.01"), "MXN", "DEBIT_LIMIT_EXCEEDED");
    }

    @ParameterizedTest(name = "la moneda {0} se rechaza")
    @ValueSource(strings = {"USD", "EUR", "mxn"})
    void rejectsUnsupportedCurrency(String currency) {
        assertViolation(TransactionType.CREDIT, new BigDecimal("100.00"), currency, "UNSUPPORTED_CURRENCY");
    }

    private void assertViolation(TransactionType type, BigDecimal amount, String currency, String expectedCode) {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> rules.validate(type, amount, currency));
        assertThat(exception.getCode()).isEqualTo(expectedCode);
    }
}
