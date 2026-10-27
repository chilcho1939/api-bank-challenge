# ADR-0002: Spring MVC con virtual threads en lugar de WebFlux

**Estado:** Aceptada

## Contexto

Se esperan millones de transacciones al día. Cada request espera una llamada HTTP al proveedor (hasta 3 s en el peor caso) y accede a PostgreSQL. Con el modelo clásico de un thread de plataforma por request, esas esperas agotarían el pool de threads del servidor.

## Decisión

- Spring MVC (modelo bloqueante) con **virtual threads** habilitados (`spring.threads.virtual.enabled=true`).
- Cliente HTTP bloqueante (`RestClient` sobre el `HttpClient` del JDK).
- El pool de conexiones de Hikari (`maximum-pool-size: 20`, configurable con `DB_POOL_SIZE`) actúa como **contrapresión** explícita hacia PostgreSQL.

## Alternativas consideradas

- **Spring WebFlux (reactivo):** escala igual para I/O, pero el código es más difícil de leer, depurar y probar. Además, JPA es bloqueante, así que habría que cambiar a R2DBC. No aporta ventaja de rendimiento relevante frente a virtual threads en este caso.
- **MVC con threads de plataforma y un pool grande:** cada thread consume memoria y el número de requests concurrentes queda limitado por el tamaño del pool.

## Consecuencias

- Código secuencial y directo; el stack trace de un error es legible.
- Miles de requests pueden esperar al proveedor de forma concurrente sin agotar threads del sistema operativo.
- **El cuello de botella pasa a ser el pool de conexiones.** Es intencional: protege a PostgreSQL. Por eso la llamada al proveedor se hace **sin retener una conexión** (ver ADR-0004).
- Hay que evitar bloques `synchronized` largos alrededor de I/O, que fijan (*pin*) el virtual thread a su carrier en Java 21. El código del servicio no los usa.
