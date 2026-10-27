# ADR-0005: Resiliencia ante el proveedor externo

**Estado:** Aceptada

## Contexto

El proveedor puede tardar, caerse o responder errores. Sin protección, un proveedor lento acumula requests esperando y arrastra al servicio completo. El contrato **no incluye una llave de idempotencia**, así que el proveedor no puede detectar una solicitud duplicada.

## Decisión

Resilience4j usado como **librería, en código** (sin anotaciones), con `RestClient` sobre el `HttpClient` del JDK.

- **Timeouts:** conexión de 1 s y lectura de 3 s, configurables (`provider.connect-timeout`, `provider.read-timeout`).
- **Reintentos solo en fallas de conexión** (`ConnectException`, `HttpConnectTimeoutException`), hasta 3 intentos con 200 ms de espera. Es el único caso en que hay certeza de que la solicitud nunca llegó al proveedor.
- **Un timeout de lectura o un 5xx nunca se reintenta**: el proveedor pudo haberlo ejecutado, y repetir un DEBIT podría cobrarlo dos veces.
- **Circuit breaker:** se abre si al menos el 50% de las últimas 10 llamadas fallan (con un mínimo de 5) y vuelve a probar a los 10 s, con 2 llamadas en *half-open*.
  - Solo cuentan como falla las fallas técnicas (excepciones).
  - Un rechazo de negocio (`INSUFFICIENT_FUNDS`) es una respuesta sana y no abre el circuito.
- El retry envuelve al circuit breaker: cada intento cuenta en sus métricas, y con el circuito abierto ya no se reintenta.

| Situación | Resultado | ¿Reintenta? | ¿Cuenta como falla? |
|---|---|---|---|
| 200 `APPROVED` | `EXECUTED` | – | No |
| 4xx/5xx con `REJECTED` | `REJECTED` | No | No |
| Conexión rechazada | `FAILED` | Sí, hasta 3 | Sí |
| Circuito abierto | `FAILED` | No | – |
| Timeout de lectura | `UNKNOWN` | No | Sí |
| 5xx sin cuerpo válido | `UNKNOWN` | No | Sí |

## Alternativas consideradas

- **Anotaciones `@CircuitBreaker` / `@Retry`:** dependen de proxies AOP, fallan en silencio con auto-invocación y esconden el orden de los decoradores. En código son pocas líneas, explícitas y testeables sin Spring.
- **Reintentar cualquier error:** riesgo de doble cargo.
- **Sin reintentos:** se pierde el caso seguro y común de un fallo de conexión transitorio.

## Consecuencias

- Con el proveedor caído, el circuito abierto responde en microsegundos en lugar de retener requests durante el timeout. Esto es especialmente relevante con virtual threads (ADR-0002).
- `UNKNOWN` requiere conciliación (ADR-0004).
- Si el proveedor añadiera una llave de idempotencia a su contrato, los timeouts también podrían reintentarse de forma segura.
- Los umbrales están en código; en producción se moverían a configuración y se expondrían sus métricas con Micrometer.
