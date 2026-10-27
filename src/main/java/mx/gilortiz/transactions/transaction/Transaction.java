package mx.gilortiz.transactions.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transacción financiera ejecutada contra el proveedor externo.
 * <p>
 * Nace en {@link TransactionStatus#PENDING} y cambia de estado una sola vez,
 * únicamente a través de los métodos {@code mark*}. No expone setters.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    // UUID v7 (ordenado por tiempo): los inserts caen al final del índice B-tree
    // de la PK. Un v4 aleatorio fragmenta el índice con alto volumen de escritura.
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {
        // requerido por JPA
    }

    private Transaction(String idempotencyKey, String accountId, TransactionType type,
                        BigDecimal amount, String currency, String description) {
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.status = TransactionStatus.PENDING;
    }

    /** Crea una transacción nueva, siempre en estado PENDING. */
    public static Transaction pending(String idempotencyKey, String accountId, TransactionType type,
                                      BigDecimal amount, String currency, String description) {
        return new Transaction(idempotencyKey, accountId, type, amount, currency, description);
    }

    public void markExecuted(String providerTransactionId, BigDecimal balanceAfter) {
        ensurePending();
        this.status = TransactionStatus.EXECUTED;
        this.providerTransactionId = providerTransactionId;
        this.balanceAfter = balanceAfter;
    }

    public void markRejected(String code, String message) {
        ensurePending();
        this.status = TransactionStatus.REJECTED;
        this.failureCode = code;
        this.failureMessage = message;
    }

    public void markFailed(String code, String message) {
        ensurePending();
        this.status = TransactionStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
    }

    public void markUnknown(String code, String message) {
        ensurePending();
        this.status = TransactionStatus.UNKNOWN;
        this.failureCode = code;
        this.failureMessage = message;
    }

    // Un estado final nunca se sobrescribe: si un bug intenta marcar dos veces,
    // falla de inmediato en lugar de corromper el registro.
    private void ensurePending() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "La transacción %s no puede cambiar de estado: ya está en %s".formatted(id, status));
        }
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public TransactionStatus getStatus() { return status; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
