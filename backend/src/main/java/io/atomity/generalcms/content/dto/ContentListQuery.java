package io.atomity.generalcms.content.dto;

import io.atomity.generalcms.content.ContentTypes;
import io.atomity.generalcms.shared.ApiException;
import io.atomity.generalcms.shared.NulCharacters;
import io.atomity.generalcms.shared.Paging;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/** Query parameters of GET /api/v1/content/{type}. Only published items are ever listed. */
public record ContentListQuery(
        @Pattern(regexp = NulCharacters.NONE, message = NulCharacters.MESSAGE) String q,
        String sort,
        String direction,
        Integer page,
        Integer size) {

    private static final Map<String, String> SORTABLE = Map.of(
            "publishedAt", "publishedAt",
            "updatedAt", "updatedAt",
            "title", "title");

    public boolean isOrderSort() {
        return "order".equals(sort);
    }

    public Pageable toPageable(String typeKey) {
        if (isOrderSort()) {
            if (!ContentTypes.DOCS.equals(typeKey)) {
                throw ApiException.badRequest("BAD_REQUEST", "sort=order is only supported for docs");
            }
            if (direction != null && !direction.equalsIgnoreCase("asc")) {
                throw ApiException.badRequest("BAD_REQUEST", "direction must be asc when sort=order");
            }
            Pageable base = Paging.of("publishedAt", "asc", page, size, SORTABLE, "publishedAt");
            return PageRequest.of(base.getPageNumber(), base.getPageSize(), Sort.unsorted());
        }
        return Paging.of(sort, direction, page, size, SORTABLE, "publishedAt");
    }
}
