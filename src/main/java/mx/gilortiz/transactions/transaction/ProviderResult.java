package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;

/**
 * Resultado de ejecutar una transacción en el proveedor, sin detalles de HTTP.
 * Es sellado: el compilador obliga a manejar los cuatro casos.
 */
public sealed interface ProviderResult {

    /** El proveedor aprobó y devolvió el saldo actualizado. */
    record Approved(String providerTransactionId, BigDecimal balance) implements ProviderResult {}

    /** El proveedor rechazó la operación (p. ej. fondos insuficientes). */
    record Rejected(String code, String message) implements ProviderResult {}

    /** Seguro no se ejecutó: no hubo conexión o el circuito estaba abierto. */
    record Failed(String code, String message) implements ProviderResult {}

    /** No sabemos si se ejecutó (timeout, 5xx sin respuesta válida): requiere conciliación. */
    record Unknown(String code, String message) implements ProviderResult {}
}
