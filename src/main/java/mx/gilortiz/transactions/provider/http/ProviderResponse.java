package mx.gilortiz.transactions.provider.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Respuesta del proveedor. Un mismo tipo cubre éxito (transactionId, balance)
 * y rechazo (code, message); el campo status indica cuál aplica.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderResponse(String transactionId, String status, BigDecimal balance,
                               String code, String message) {
}
