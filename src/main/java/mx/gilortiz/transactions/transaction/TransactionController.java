package mx.gilortiz.transactions.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import mx.gilortiz.transactions.common.web.PageResponse;
import mx.gilortiz.transactions.transaction.dto.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * Responde 201 aunque el proveedor rechace: la transacción se creó y quedó registrada
     * con su estado. Solo los errores de formato (400), de regla de negocio (422)
     * o de idempotencia (409) impiden crearla.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse execute(
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Size(max = 100, message = "Idempotency-Key no puede exceder 100 caracteres")
            String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        Transaction transaction = service.execute(request.toCommand(idempotencyKey));
        return TransactionResponse.from(transaction);
    }

    @GetMapping
    public PageResponse<TransactionResponse> search(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page debe ser 0 o mayor") int page,
            // Tope de 100: una página sin límite con millones de filas tumbaría la BD y la red.
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "limit debe ser al menos 1")
            @Max(value = 100, message = "limit no puede exceder 100") int limit) {
        TransactionFilter filter = new TransactionFilter(accountId, status, type);
        return PageResponse.from(service.search(filter, page, limit), TransactionResponse::from);
    }
}
