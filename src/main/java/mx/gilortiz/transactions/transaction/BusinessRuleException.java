package mx.gilortiz.transactions.transaction;

/**
 * Una regla de negocio no se cumplió: la transacción no se envía al proveedor.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
