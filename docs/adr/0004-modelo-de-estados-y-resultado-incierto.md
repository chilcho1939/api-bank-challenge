# ADR-0004: Modelo de estados y manejo del resultado incierto

**Estado:** Aceptada

## Contexto

Llamar a un proveedor externo tiene tres desenlaces, no dos: aprobó, rechazó o **no sabemos**. Si la conexión se corta o vence el timeout después de enviar la solicitud, el proveedor pudo haber ejecutado el cargo. Marcar esa transacción como fallida sería falso, y reintentarla podría cobrar dos veces.

Además, si la app falla entre la llamada al proveedor y la escritura del resultado, una transacción ejecutada podría no existir en nuestra base de datos.

## Decisión

Cinco estados, donde un estado final no se puede sobrescribir:

| Estado | Cuándo |
|---|---|
| `PENDING` | Se registra **antes** de llamar al proveedor |
| `EXECUTED` | El proveedor respondió `APPROVED` |
| `REJECTED` | El proveedor respondió `REJECTED` (con 4xx o 5xx, como indica el contrato) o un 4xx sin detalle |
| `FAILED` | Certeza de que no se ejecutó: la conexión nunca se estableció o el circuit breaker estaba abierto |
| `UNKNOWN` | Incertidumbre: timeout de lectura o 5xx sin un cuerpo válido |

Flujo del servicio, **sin `@Transactional` en el método**:

1. Validar reglas.
2. `INSERT` en `PENDING` (transacción corta, con commit inmediato).
3. Llamar al proveedor **sin una conexión de base de datos retenida**.
4. `UPDATE` con el resultado (otra transacción corta).

## Alternativas consideradas

- **Guardar solo al final:** si la app muere después de que el proveedor ejecuta, no queda rastro.
- **Envolver todo en una transacción de BD:** retiene una conexión durante la llamada HTTP (hasta 3 s). Con un pool de 20 conexiones, 20 llamadas lentas bloquearían toda la base de datos.
- **Tratar timeout y error de conexión igual (un solo estado `FAILED`):** pierde la distinción que decide si es seguro reintentar.

## Consecuencias

- Toda transacción que tocó al proveedor queda registrada.
- `UNKNOWN` y los `PENDING` antiguos son el universo de **conciliación**, indexado por estado. El proceso de conciliación requiere un endpoint de consulta del proveedor que el contrato actual no ofrece; queda como siguiente paso.
- Se hacen dos escrituras por transacción en lugar de una; es el costo de la trazabilidad.
- El segundo `save` hace un `merge` (SELECT + UPDATE). Puede optimizarse con un `UPDATE` directo o con `@Version` si el volumen lo exige.
