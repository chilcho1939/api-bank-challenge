# ADR-0007: Códigos HTTP y contrato de errores

**Estado:** Aceptada

## Contexto

El cliente necesita distinguir entre una solicitud mal formada, una regla de negocio violada, un conflicto de idempotencia y el resultado del proveedor. El reto pide un error descriptivo cuando no se cumplen las reglas, y retornar la transacción creada con su estado.

## Decisión

- **201** cuando la transacción se procesó, **cualquiera que sea su estado** (incluidos `REJECTED`, `FAILED` y `UNKNOWN`). La transacción se creó y quedó registrada; su estado es el resultado de negocio.
- **400** para formato inválido (Bean Validation en el DTO, JSON mal formado, enum inexistente, parámetros fuera de rango).
- **422** para reglas de negocio violadas, validadas antes de llamar al proveedor.
- **409** para una `Idempotency-Key` reutilizada con datos distintos.
- **500** genérico para errores inesperados: el detalle va al log y nunca al cliente.
- Formato **RFC 9457 (`ProblemDetail`)**, con dos propiedades propias:
  - `code`: estable y en inglés, para clientes programáticos.
  - `errors`: lista de mensajes de validación.
  - El `detail` va en español, para personas.
- `GlobalExceptionHandler` extiende `ResponseEntityExceptionHandler` para que los errores propios de Spring (404, 405, JSON ilegible) usen el mismo formato.
- Los mensajes del proveedor se guardan tal cual, sin traducir.

## Alternativas consideradas

- **Responder 4xx cuando el proveedor rechaza:** mezcla "tu solicitud está mal" con "el proveedor dijo que no", y oculta que la transacción sí se registró.
- **Validar las reglas de negocio con anotaciones en el DTO:** las mezcla con el formato, devuelve 400 en lugar de 422 y las ata a la capa web.
- **Formato de error propio:** RFC 9457 es un estándar que Spring soporta de forma nativa.

## Consecuencias

- El cliente decide por el código HTTP y por `code`, no por el texto.
- El contrato de errores es uniforme para cualquier falla.
