package mx.gilortiz.transactions.common.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Página de resultados con un formato JSON propio y estable.
 * No se serializa Page de Spring directamente: su estructura interna no es un contrato público.
 */
public record PageResponse<T>(List<T> content, int page, int limit, long totalElements, int totalPages) {
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
