package io.atomity.generalcms.post.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.atomity.generalcms.content.ContentTypeDefinition;
import io.atomity.generalcms.content.ContentTypes;
import io.atomity.generalcms.content.FieldValidator;
import io.atomity.generalcms.media.entity.Media;
import io.atomity.generalcms.media.repository.MediaRepository;
import io.atomity.generalcms.post.dto.PostListQuery;
import io.atomity.generalcms.post.dto.PostRequest;
import io.atomity.generalcms.post.dto.PostResponse;
import io.atomity.generalcms.post.dto.PostSummaryResponse;
import io.atomity.generalcms.post.entity.Post;
import io.atomity.generalcms.post.entity.PostEdit;
import io.atomity.generalcms.post.entity.PostStatus;
import io.atomity.generalcms.post.repository.PostRepository;
import io.atomity.generalcms.post.repository.PostSpecifications;
import io.atomity.generalcms.shared.ApiException;
import io.atomity.generalcms.shared.Slugs;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final int MAX_SLUG_LENGTH = 160;

    private final PostRepository postRepository;
    private final MediaRepository mediaRepository;

    public PostService(PostRepository postRepository, MediaRepository mediaRepository) {
        this.postRepository = postRepository;
        this.mediaRepository = mediaRepository;
    }

    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> list(PostListQuery query) {
        if (query.type() != null && !query.type().isBlank()) {
            ContentTypes.require(query.type());
        }
        Specification<Post> spec = Specification.where(PostSpecifications.hasType(query.type()))
                .and(PostSpecifications.hasStatus(query.status()))
                .and(PostSpecifications.matches(query.q()));
        return postRepository.findAll(spec, query.toPageable()).map(PostSummaryResponse::of);
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID id) {
        return PostResponse.of(find(id));
    }

    /** A draft can be saved with anything missing; only what is present has to be well-formed. */
    @Transactional
    public PostResponse create(PostRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "type is required when creating content");
        }
        ContentTypeDefinition type = ContentTypes.require(request.type());
        Post post = Post.create(type.key(), resolveEdit(type, request, null));
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse duplicate(UUID id) {
        Post source = postRepository.findForDuplication(id).orElseThrow(() -> ApiException.notFound("Content"));
        String slug = copySlug(source);
        return create(new PostRequest(source.getContentType(), source.getTitle(), slug, source.getExcerpt(),
                source.getContent().deepCopy(), source.getFields().deepCopy(), null));
    }

    private String copySlug(Post source) {
        for (long copy = 1; ; copy++) {
            String suffix = copy == 1 ? "-copy" : "-copy-" + copy;
            String slug = truncate(source.getSlug(), MAX_SLUG_LENGTH - suffix.length()) + suffix;
            if (!postRepository.existsByContentTypeAndSlug(source.getContentType(), slug)) {
                return slug;
            }
        }
    }

    /**
     * Saves an edit. The request carries the version the editor loaded; if the item has
     * moved on since, the save is refused instead of silently overwriting the newer one.
     */
    @Transactional
    public PostResponse update(UUID id, PostRequest request) {
        if (request.version() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "version is required when updating content");
        }
        Post post = find(id);
        if (request.type() != null && !request.type().isBlank() && !request.type().equals(post.getContentType())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "The content type can't be changed after creation");
        }
        if (post.getVersion() != request.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "This content was changed somewhere else since you opened it. Reload to get the latest version.");
        }
        ContentTypeDefinition type = ContentTypes.require(post.getContentType());
        PostEdit edit = resolveEdit(type, request, id);
        // Live content must stay publishable: refuse a save that would leave it incomplete.
        if (post.getStatus() == PostStatus.PUBLISHED) {
            List<String> missing = missingForPublication(type, edit);
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PUBLISHABLE",
                        "Published content can't be saved without " + join(missing) + ". Unpublish it first.");
            }
        }
        post.edit(edit);
        // Flush so the response carries the incremented version.
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    /** Publishing needs a title, something to read and every required field of the type. */
    @Transactional
    public PostResponse publish(UUID id) {
        Post post = find(id);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            return PostResponse.of(post);
        }
        ContentTypeDefinition type = ContentTypes.require(post.getContentType());
        List<String> missing = missingForPublication(type, post.getTitle(), post.getContentText().isBlank()
                && post.getInlineImages().isEmpty(), post.getFields());
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PUBLISHABLE",
                    "Add " + join(missing) + " before publishing.");
        }
        post.publish();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse unpublish(UUID id) {
        Post post = find(id);
        if (post.getStatus() == PostStatus.DRAFT) {
            return PostResponse.of(post);
        }
        post.unpublish();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse archive(UUID id) {
        Post post = find(id);
        if (post.getStatus() == PostStatus.ARCHIVED) {
            return PostResponse.of(post);
        }
        post.archive();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    /**
     * Permanently deletes a draft or archived item. Published items must be unpublished
     * or archived first so live public content cannot be removed accidentally.
     */
    @Transactional
    public void delete(UUID id) {
        Post post = find(id);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            throw ApiException.conflict("CANNOT_DELETE_PUBLISHED",
                    "Published content can't be deleted. Unpublish or archive it first.");
        }
        postRepository.delete(post);
    }

    private Post find(UUID id) {
        return postRepository.findById(id).orElseThrow(() -> ApiException.notFound("Content"));
    }

    /** Validates the request against the type and the database and turns it into a resolved edit. */
    private PostEdit resolveEdit(ContentTypeDefinition type, PostRequest request, UUID selfId) {
        String title = clean(request.title());
        ContentAnalyzer.Analysis content = ContentAnalyzer.analyze(request.content());
        ObjectNode fields = FieldValidator.normalize(type, request.fields());
        List<Media> inlineImages = resolveImages(content.imageIds());
        return new PostEdit(title, resolveSlug(type.key(), request.slug(), title, selfId), clean(request.excerpt()),
                request.content(), content.text(), fields, inlineImages);
    }

    private static List<String> missingForPublication(ContentTypeDefinition type, PostEdit edit) {
        return missingForPublication(type, edit.title(), edit.contentText().isBlank() && edit.inlineImages().isEmpty(),
                edit.fields());
    }

    private static List<String> missingForPublication(ContentTypeDefinition type, String title, boolean noContent,
                                                      com.fasterxml.jackson.databind.JsonNode fields) {
        List<String> missing = new ArrayList<>();
        if (title.isBlank()) {
            missing.add("a title");
        }
        if (noContent) {
            missing.add("some content");
        }
        FieldValidator.missingRequired(type, fields).forEach(label -> missing.add("the " + label + " field"));
        return missing;
    }

    private String resolveSlug(String type, String requested, String title, UUID selfId) {
        String slug = requested == null || requested.isBlank() ? Slugs.slugify(title) : requested;
        if (slug.isEmpty()) {
            // An untitled draft still needs a unique URL; it can be renamed later.
            slug = "draft-" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36);
        }
        slug = truncate(slug, MAX_SLUG_LENGTH);
        boolean taken = selfId == null
                ? postRepository.existsByContentTypeAndSlug(type, slug)
                : postRepository.existsByContentTypeAndSlugAndIdNot(type, slug, selfId);
        if (taken) {
            throw ApiException.conflict("SLUG_TAKEN", "Another " + type + " item already uses the slug \"" + slug + "\"");
        }
        return slug;
    }

    /** Loads the images embedded in the body; an unknown id must fail the save rather than be dropped. */
    private List<Media> resolveImages(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        var unique = new LinkedHashSet<>(ids);
        Map<UUID, Media> found = mediaRepository.findAllById(unique).stream()
                .collect(Collectors.toMap(Media::getId, Function.identity()));
        if (found.size() != unique.size()) {
            throw ApiException.badRequest("UNKNOWN_REFERENCE", "One or more images do not exist");
        }
        return unique.stream().map(found::get).toList();
    }

    /** "a title", "a title and some content", "a, b and c". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).replaceAll("-+$", "");
    }
}
