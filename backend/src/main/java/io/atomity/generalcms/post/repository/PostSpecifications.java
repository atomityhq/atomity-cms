package io.atomity.generalcms.post.repository;

import io.atomity.generalcms.post.entity.Post;
import io.atomity.generalcms.post.entity.PostStatus;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/** Filters for the lists. Each returns null when unused, which Specification.where ignores. */
public final class PostSpecifications {

    private PostSpecifications() {
    }

    public static Specification<Post> hasType(String contentType) {
        return contentType == null || contentType.isBlank() ? null : (root, query, cb) -> cb.equal(root.get("contentType"), contentType);
    }

    public static Specification<Post> hasStatus(PostStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Case-insensitive substring match on title, excerpt, slug and body text. Uses ILIKE,
     * which the pg_trgm indexes from V1 can serve.
     */
    public static Specification<Post> matches(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(q.strip()) + "%";
        return (root, query, cb) -> {
            var hcb = (HibernateCriteriaBuilder) cb;
            return cb.or(
                    hcb.ilike(root.get("title"), pattern, '\\'),
                    hcb.ilike(root.get("excerpt"), pattern, '\\'),
                    hcb.ilike(root.get("slug"), pattern, '\\'),
                    hcb.ilike(root.get("contentText"), pattern, '\\'));
        };
    }

    /**
     * Sorts docs by numeric fields.order ASC (missing/null values last),
     * then title ASC, then id ASC for deterministic pagination.
     */
    public static Specification<Post> orderByDocsOrder() {
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType() && long.class != query.getResultType()) {
                var hcb = (HibernateCriteriaBuilder) cb;
                var rawOrder = hcb.function("jsonb_extract_path_text", String.class, root.get("fields"), hcb.literal("order"));
                var numericOrder = hcb.cast(hcb.nullif(rawOrder, hcb.literal("")), Integer.class);
                query.orderBy(
                        hcb.asc(numericOrder, false),
                        cb.asc(root.get("title")),
                        cb.asc(root.get("id"))
                );
            }
            return null;
        };
    }

    /** The user's text is matched literally: % and _ are not wildcards. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
