package mx.gilortiz.transactions.transaction;

/** La llave de idempotencia ya se usó con datos distintos: no es un reintento legítimo. */
public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("La llave de idempotencia %s ya se usó con datos distintos".formatted(idempotencyKey));
    }
}
