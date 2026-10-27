package mx.gilortiz.transactions.transaction;

/**
 * Ciclo de vida de una transacción. Distingue "seguro no se ejecutó" (FAILED)
 * de "pudo haberse ejecutado" (UNKNOWN), porque solo el primero es seguro de reintentar.
 */
public enum TransactionStatus {
    /** Registrada; aún sin respuesta del proveedor. */
    PENDING,

    /** El proveedor aprobó la operación. */
    EXECUTED,

    /** El proveedor rechazó la operación de forma explícita. */
    REJECTED,

    /** No se ejecutó: el proveedor no fue contactado o rechazó la conexión. */
    FAILED,

    /** Resultado incierto (timeout, 5xx sin respuesta válida); requiere conciliación. */
    UNKNOWN
}
