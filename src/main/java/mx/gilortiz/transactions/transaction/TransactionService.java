package mx.gilortiz.transactions.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRules rules;
    private final TransactionProvider provider;
    private final TransactionRepository repository;

    public TransactionService(TransactionRules rules,
                              TransactionProvider provider,
                              TransactionRepository repository) {
        this.rules = rules;
        this.provider = provider;
        this.repository = repository;
    }

    /**
     * Valida, registra la transacción en PENDING, la ejecuta en el proveedor y guarda el resultado.
     * <p>
     * Sin {@code @Transactional} a propósito: cada {@code save} es una transacción corta
     * y la llamada HTTP ocurre sin retener una conexión de base de datos.
     */
    public Transaction execute(ExecuteTransactionCommand command) {
        rules.validate(command.type(), command.amount(), command.currency());

        Optional<Transaction> previous = findPrevious(command);
        if (previous.isPresent()) {
            return previous.get();
        }

        Transaction transaction;
        try {
            // Se registra antes de llamar al proveedor: si algo falla a la mitad, queda rastro.
            transaction = repository.save(Transaction.pending(command.idempotencyKey(), command.accountId(),
                    command.type(), command.amount(), command.currency(), command.description()));
        } catch (DataIntegrityViolationException e) {
            // Otra solicitud con la misma llave ganó la carrera: se devuelve la suya.
            return findPrevious(command).orElseThrow(() -> e);
        }

        applyResult(transaction, provider.execute(transaction));

        // Si este save falla, la transacción queda en PENDING y se resuelve por conciliación.
        Transaction saved = repository.save(transaction);
        log.info("Transacción {} ({} {} {}) terminó en {}", saved.getId(), saved.getType(),
                saved.getAmount(), saved.getCurrency(), saved.getStatus());
        return saved;
    }

    /**
     * Consulta paginada, de la más reciente a la más antigua.
     * El id desempata cuando dos transacciones tienen el mismo createdAt,
     * para que una fila no se repita ni se pierda entre páginas.
     */
    public Page<Transaction> search(TransactionFilter filter, int page, int limit) {
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return repository.findAll(filter.toSpecification(), PageRequest.of(page, limit, newestFirst));
    }

    private Optional<Transaction> findPrevious(ExecuteTransactionCommand command) {
        if (command.idempotencyKey() == null) {
            return Optional.empty();
        }
        return repository.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> requireSamePayload(existing, command));
    }

    // Misma llave con datos distintos es un error del cliente, no un reintento.
    private static Transaction requireSamePayload(Transaction existing, ExecuteTransactionCommand command) {
        boolean samePayload = existing.getAccountId().equals(command.accountId())
                && existing.getType() == command.type()
                && existing.getAmount().compareTo(command.amount()) == 0
                && existing.getCurrency().equals(command.currency());
        if (!samePayload) {
            throw new IdempotencyKeyConflictException(command.idempotencyKey());
        }
        return existing;
    }

    private static void applyResult(Transaction transaction, ProviderResult result) {
        switch (result) {
            case ProviderResult.Approved(String providerTransactionId, BigDecimal balance) ->
                    transaction.markExecuted(providerTransactionId, balance);
            case ProviderResult.Rejected(String code, String message) ->
                    transaction.markRejected(code, message);
            case ProviderResult.Failed(String code, String message) ->
                    transaction.markFailed(code, message);
            case ProviderResult.Unknown(String code, String message) ->
                    transaction.markUnknown(code, message);
        }
    }
}
