package io.atomity.generalcms.content.service;

import io.atomity.generalcms.content.ContentTypeDefinition;
import io.atomity.generalcms.content.ContentTypes;
import io.atomity.generalcms.content.dto.ContentListQuery;
import io.atomity.generalcms.content.dto.ContentResponse;
import io.atomity.generalcms.content.dto.ContentSummaryResponse;
import io.atomity.generalcms.post.entity.Post;
import io.atomity.generalcms.post.entity.PostStatus;
import io.atomity.generalcms.post.repository.PostRepository;
import io.atomity.generalcms.post.repository.PostSpecifications;
import io.atomity.generalcms.shared.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to published content for independent websites. Every lookup is filtered by
 * status, so drafts and archived items are indistinguishable from items that don't exist.
 */
@Service
public class ContentService {

    private final PostRepository postRepository;

    public ContentService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    @Transactional(readOnly = true)
    public Page<ContentSummaryResponse> list(String typeKey, ContentListQuery query) {
        ContentTypeDefinition type = knownType(typeKey);
        Pageable pageable = query.toPageable(type.key());
        Specification<Post> spec = Specification.where(PostSpecifications.hasType(type.key()))
                .and(PostSpecifications.hasStatus(PostStatus.PUBLISHED))
                .and(PostSpecifications.matches(query.q()));
        if (query.isOrderSort()) {
            spec = spec.and(PostSpecifications.orderByDocsOrder());
        }
        return postRepository.findAll(spec, pageable).map(ContentSummaryResponse::of);
    }

    @Transactional(readOnly = true)
    public ContentResponse getBySlug(String typeKey, String slug) {
        ContentTypeDefinition type = knownType(typeKey);
        return postRepository.findByContentTypeAndSlugAndStatus(type.key(), slug, PostStatus.PUBLISHED)
                .map(ContentResponse::of)
                .orElseThrow(() -> ApiException.notFound("Content"));
    }

    /** In the public API an unknown type is just another thing that doesn't exist. */
    private static ContentTypeDefinition knownType(String key) {
        return ContentTypes.find(key).orElseThrow(() -> ApiException.notFound("Content type"));
    }
}
