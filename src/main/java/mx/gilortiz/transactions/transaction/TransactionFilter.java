package mx.gilortiz.transactions.transaction;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Filtros opcionales de la consulta. Un filtro nulo no se aplica. */
public record TransactionFilter(String accountId, TransactionStatus status, TransactionType type) {

    // Solo se agregan las condiciones presentes: el SQL generado queda simple
    // y Postgres puede usar el índice adecuado para cada combinación.
    Specification<Transaction> toSpecification() {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (accountId != null) {
                predicates.add(builder.equal(root.get("accountId"), accountId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
