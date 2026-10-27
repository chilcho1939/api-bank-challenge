# ADR-0003: PostgreSQL, modelo de datos y paginación

**Estado:** Aceptada

## Contexto

El servicio registra transacciones financieras con alto volumen de escritura y las consulta por cuenta, estado y tipo. El reto deja la persistencia a elección y pide justificarla.

## Decisión

**PostgreSQL 17**, con el esquema versionado por **Flyway**. Hibernate solo valida el esquema (`ddl-auto: validate`).

Modelo de la tabla `transactions`:

- **Montos en `NUMERIC(19,2)`** (`BigDecimal` en Java). Los tipos flotantes no representan decimales con exactitud.
- **Llave primaria UUID v7**, generada por Hibernate. Es ordenada por tiempo, así que los inserts caen al final del índice B-tree; un UUID v4 aleatorio lo fragmenta con alto volumen.
- **`idempotency_key` con `UNIQUE`**, opcional. Es la garantía final contra duplicados concurrentes (ver ADR-0006).
- **`CHECK`** sobre tipo, estado y monto positivo. Protegen los invariantes aunque otro proceso escriba en la tabla.
- **Fechas `TIMESTAMPTZ` en UTC.**
- **Solo dos índices secundarios**, alineados a las consultas reales:
  - `(account_id, created_at DESC)`: movimientos de una cuenta, recientes primero.
  - `(status, created_at DESC)`: localizar transacciones `UNKNOWN` o `PENDING` para conciliación.
  - `type` (dos valores) no justifica un índice propio.

Consulta:

- Filtros dinámicos con `Specification`: solo se agregan las condiciones presentes, para que el planner use el índice adecuado. Se evita el patrón `(:x IS NULL OR col = :x)`.
- Paginación `page`/`limit` (la pide el contrato), con `limit` máximo de 100 y orden `created_at DESC, id DESC`. El `id` desempata para que la paginación sea estable.

## Alternativas consideradas

- **En memoria:** no sobrevive reinicios ni permite más de una instancia.
- **MongoDB:** también está en el stack de Spin, pero el modelo es tabular y fijo, y las garantías de unicidad transaccional y de tipos numéricos exactos son más directas en PostgreSQL.
- **SQLite:** no está pensada para escritura concurrente de alto volumen.

## Consecuencias

- La integridad se garantiza en la base de datos, no solo en el código.
- Cada índice adicional encarece cada `INSERT`; se agregan solo con una consulta que los justifique.
- **La paginación por offset se degrada en páginas profundas** (`OFFSET` recorre las filas saltadas y `COUNT(*)` cuenta todas). La evolución natural es **keyset pagination** (`WHERE (created_at, id) < (:c, :i)`), que usa el índice directamente.
- A mayor escala: particionar la tabla por fecha (`created_at`) y mover la consulta a una réplica de lectura.
- Una migración ya aplicada nunca se edita; los cambios van en una nueva versión (`V2__...`).
