-- Montos en NUMERIC: los tipos flotantes no representan decimales con exactitud.
-- Los CHECK protegen los invariantes aunque otro proceso escriba en la tabla.
CREATE TABLE transactions (
    id                      UUID           PRIMARY KEY,
    -- Opcional; UNIQUE es la garantía final contra duplicados concurrentes.
    -- Postgres admite múltiples NULL en una columna UNIQUE.
    idempotency_key         VARCHAR(100)   UNIQUE,
    account_id              VARCHAR(64)    NOT NULL,
    type                    VARCHAR(10)    NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)        NOT NULL,
    description             VARCHAR(255),
    status                  VARCHAR(20)    NOT NULL
                            CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'UNKNOWN')),
    provider_transaction_id VARCHAR(100),
    balance_after           NUMERIC(19, 2),
    failure_code            VARCHAR(50),
    failure_message         VARCHAR(500),
    created_at              TIMESTAMPTZ    NOT NULL,
    updated_at              TIMESTAMPTZ    NOT NULL
);

-- Solo se indexan las consultas principales: con alto volumen de escritura,
-- cada índice adicional encarece cada INSERT. `type` (2 valores) no justifica índice propio.

-- Movimientos de una cuenta, más recientes primero.
CREATE INDEX idx_transactions_account_created
    ON transactions (account_id, created_at DESC);

-- Filtro por estado, p. ej. localizar UNKNOWN para conciliación.
CREATE INDEX idx_transactions_status_created
    ON transactions (status, created_at DESC);