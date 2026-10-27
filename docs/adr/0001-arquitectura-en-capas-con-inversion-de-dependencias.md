# ADR-0001: Arquitectura en capas con inversión de dependencias hacia el proveedor

**Estado:** Aceptada

## Contexto

El reto valora la separación de capas, la inversión de dependencias, el desacoplamiento del cliente HTTP y el pragmatismo. El servicio tiene dos endpoints y una sola integración externa.

## Decisión

- Capas clásicas (controller → service → repository) **organizadas por funcionalidad** (`transaction/`) y no por tipo de clase.
- Inversión de dependencias **solo donde aporta: el proveedor externo**. La interfaz `TransactionProvider` vive en `transaction/`, porque la abstracción pertenece a quien la usa. Su implementación (`HttpTransactionProvider`) vive en `provider/http/`.
- El resultado del proveedor es un tipo sellado (`ProviderResult`: `Approved | Rejected | Failed | Unknown`) sin detalles de HTTP. El servicio lo procesa con un `switch` exhaustivo.
- Las reglas de negocio viven en `TransactionRules`, que recibe solo los datos que evalúa y no depende de la capa web.
- La entidad `Transaction` no expone setters: cambia de estado solo mediante métodos de negocio (`markExecuted`, `markRejected`, ...) y una sola vez.

## Alternativas consideradas

- **Arquitectura hexagonal completa** (puertos y adaptadores en todas las fronteras, modelo de dominio separado de la entidad JPA): duplica modelos y mapeos en un servicio de dos endpoints. Se descartó por sobre-diseño; se tomó solo su idea central (el puerto hacia el proveedor).
- **Llamar a `RestClient` directamente desde el servicio:** acopla la orquestación al protocolo y obliga a probar el servicio con un servidor HTTP.

## Consecuencias

- `transaction/` no depende de ningún otro paquete; `provider/http/` depende de `transaction/`. No hay ciclos.
- El servicio se prueba con un mock de `TransactionProvider` y el cliente HTTP se prueba aislado con WireMock.
- Cambiar de protocolo (gRPC, mensajería) solo requiere otra implementación de `TransactionProvider`.
- La entidad JPA cruza hacia el adaptador HTTP en modo lectura. Es un acoplamiento aceptado a cambio de no duplicar el modelo.
