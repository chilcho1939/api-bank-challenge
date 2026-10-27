package mx.gilortiz.transactions.transaction;

/**
 * Puerto hacia el proveedor externo. El servicio depende de esta interfaz, no del cliente HTTP.
 * <p>
 * Contrato: nunca lanza excepciones por fallas del proveedor; toda falla se traduce
 * a un {@link ProviderResult}.
 */
public interface TransactionProvider {

    ProviderResult execute(Transaction transaction);
}
