# ADR-0006: Idempotencia con Idempotency-Key

**Estado:** Aceptada

## Contexto

Un cliente que no recibe respuesta (por un corte de red o un timeout de su lado) no sabe si su transacción se ejecutó y tiende a reintentar. Sin protección, cada reintento es un cargo nuevo.

## Decisión

- Header **opcional** `Idempotency-Key` (máximo 100 caracteres) en `POST /transactions`.
- Misma llave y mismos datos (cuenta, tipo, monto comparado por valor y moneda): se devuelve la transacción original **sin llamar al proveedor**.
- Misma llave con datos distintos: **409** `IDEMPOTENCY_KEY_REUSED`. Es un error del cliente, no un reintento.
- **La garantía final es un `UNIQUE` en la columna `idempotency_key`.** Dos solicitudes simultáneas pueden no encontrar la llave al mismo tiempo; la segunda que intente insertar falla con `DataIntegrityViolationException`, y el servicio devuelve la transacción ganadora.
- Solo la solicitud que logra insertar llama al proveedor.

## Alternativas consideradas

- **Bloqueos en memoria o cachés locales:** no funcionan con varias instancias del servicio.
- **Redis con expiración:** agrega infraestructura; el constraint en la base de datos resuelve el caso con lo que ya existe.
- **Llave obligatoria:** más segura, pero rompe el contrato de request del reto.

## Consecuencias

- Reintentar es seguro para el cliente; el servicio puede escalar horizontalmente sin coordinación extra.
- Probado con 10 virtual threads concurrentes con la misma llave: una sola fila y una sola llamada al proveedor (`TransactionIntegrationTest`).
- Si la solicitud original sigue en curso, el reintento recibe la transacción en `PENDING`, que es su estado real.
- Un reintento responde 201 igual que la creación; podría responder 200 para distinguirlo.
- Las llaves no expiran; a gran escala convendría una política de retención.
