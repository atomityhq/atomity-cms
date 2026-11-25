package io.atomity.generalcms.content;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.generalcms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole flow: the admin creates, validates and publishes; the public API serves only what is published. */
class ContentApiTest extends IntegrationTest {

    private static final Map<String, Map<String, Object>> VALID_FIELDS = Map.of(
            "news", Map.of(),
            "career", Map.of("department", "Engineering", "location", "Mannheim", "employmentType", "intern",
                    "applyUrl", "https://example.org/apply", "closingDate", "2026-12-31", "remote", true),
            "docs", Map.of("section", "Getting started", "order", 2),
            "kb", Map.of("symptom", "Onboarding fails with a 403", "product", "Atomity Agent", "affectedVersions", "1.2 - 1.4"));

    private static Map<String, Object> doc(String text) {
        Map<String, Object> paragraph = text.isEmpty()
                ? Map.of("type", "paragraph")
                : Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text)));
        return Map.of("type", "doc", "content", List.of(paragraph));
    }

    private Map<String, Object> body(String type, String title, Map<String, Object> fields) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("excerpt", "About " + title);
        body.put("content", doc("Body of " + title));
        body.put("fields", fields);
        return body;
    }

    private JsonNode create(String type, String title, Map<String, Object> fields) throws Exception {
        return data(call(post("/api/v1/posts"), body(type, title, fields)).andExpect(status().isCreated()));
    }

    private JsonNode createAndPublish(String type, String title) throws Exception {
        JsonNode created = create(type, title, VALID_FIELDS.get(type));
        return data(call(post("/api/v1/posts/" + created.path("id").asText() + "/publish")).andExpect(status().isOk()));
    }

    // ---- creating and updating

    @Test
    void duplicateCreatesIndependentPrivateDraft() throws Exception {
        JsonNode source = createAndPublish("career", "Internship");
        String path = "/api/v1/posts/" + source.path("id").asText();
        JsonNode copy = data(call(post(path + "/duplicate")).andExpect(status().isCreated()));
        for (String field : List.of("type", "title", "excerpt", "content", "fields")) {
            assertThat(copy.get(field)).isEqualTo(source.get(field));
        }
        assertThat(copy.get("id")).isNotEqualTo(source.get("id"));
        assertThat(copy.get("publishedAt").isNull()).isTrue();
        assertThat(copy.get("version").asLong()).isZero();
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        assertThat(data(call(get(path)))).isEqualTo(source);
        String publicPath = "/api/v1/content/career/slug/" + copy.get("slug").asText();
        mockMvc.perform(get(publicPath)).andExpect(status().isNotFound());
        call(post("/api/v1/posts/" + copy.get("id").asText() + "/publish")).andExpect(status().isOk());
        mockMvc.perform(get(publicPath)).andExpect(status().isOk());
    }

    @Test
    void repeatedCopiesFitSlugLimit() throws Exception {
        var request = body("career", "Incomplete", Map.of());
        request.put("slug", "a".repeat(160));
        JsonNode source = data(call(post("/api/v1/posts"), request).andExpect(status().isCreated()));
        String path = "/api/v1/posts/" + source.get("id").asText() + "/duplicate";
        for (String suffix : List.of("-copy", "-copy-2")) {
            JsonNode copy = data(call(post(path)).andExpect(status().isCreated()));
            assertThat(copy.get("slug").asText()).isEqualTo("a".repeat(160 - suffix.length()) + suffix);
        }
    }

    @Test
    void duplicateRequiresAdminAndExistingSource() throws Exception {
        String path = "/api/v1/posts/00000000-0000-0000-0000-000000000001/duplicate";
        mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        call(post(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_FOUND"));
    }

    @Test
    void everyTypeCanBeCreatedAsADraft() throws Exception {
        for (String type : List.of("news", "career", "docs", "kb")) {
            JsonNode created = create(type, "A " + type + " item", VALID_FIELDS.get(type));
            assertThat(created.path("type").asText()).isEqualTo(type);
            assertThat(created.path("status").asText()).isEqualTo("DRAFT");
            assertThat(created.path("slug").asText()).isEqualTo("a-" + type + "-item");
            assertThat(created.path("publishedAt").isNull()).isTrue();
            assertThat(created.path("fields").size()).isEqualTo(VALID_FIELDS.get(type).size());
        }
    }

    @Test
    void everyTypeCanBeUpdated() throws Exception {
        for (String type : List.of("news", "career", "docs", "kb")) {
            JsonNode created = create(type, "Before " + type, VALID_FIELDS.get(type));
            Map<String, Object> edit = body(type, "After " + type, VALID_FIELDS.get(type));
            edit.put("version", created.path("version").asLong());
            JsonNode updated = data(call(put("/api/v1/posts/" + created.path("id").asText()), edit).andExpect(status().isOk()));
            assertThat(updated.path("title").asText()).isEqualTo("After " + type);
            assertThat(updated.path("version").asLong()).isGreaterThan(created.path("version").asLong());
        }
    }

    @Test
    void unsupportedContentTypesOnPostAndPutReturn415() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";

        call(post("/api/v1/posts").contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        call(put("/api/v1/posts/" + id).contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void malformedJsonOnPostAndPutRemains400() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";

        call(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("MALFORMED_REQUEST"));

        call(put("/api/v1/posts/" + id).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("MALFORMED_REQUEST"));
    }

    private List<JsonNode> malformedDocuments() throws Exception {
        return List.of(
                objectMapper.readTree("""
                        {"type":"doc","content":{"item":{"type":"paragraph",
                          "content":[{"type":"text","text":"Hello world"}]}}}
                        """),
                objectMapper.readTree("""
                        {"type":"doc","content":[{"type":"paragraph","content":[{"type":42}]}]}
                        """),
                objectMapper.readTree("""
                        {"type":"doc","content":[{"type":"paragraph","content":[
                          {"type":"text","text":"Hello world","marks":{}}]}]}
                        """),
                objectMapper.readTree("""
                        {"type":"doc","content":[{"type":"paragraph","content":[
                          {"type":"text","text":"Hello world","marks":[null]}]}]}
                        """));
    }

    @Test
    void malformedStructuresCannotBeCreated() throws Exception {
        for (JsonNode malformed : malformedDocuments()) {
            Map<String, Object> request = body("news", "Malformed", Map.of());
            request.put("content", malformed);
            call(post("/api/v1/posts"), request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("INVALID_CONTENT"));
            call(get("/api/v1/posts")).andExpect(jsonPath("$.meta.totalElements").value(0));
        }
    }

    @Test
    void malformedStructuresCannotOverwritePublishedContent() throws Exception {
        JsonNode original = createAndPublish("news", "Keep original");
        String id = original.path("id").asText();
        JsonNode originalPublic = data(mockMvc.perform(get("/api/v1/content/news/slug/keep-original"))
                .andExpect(status().isOk()));
        for (JsonNode malformed : malformedDocuments()) {
            Map<String, Object> edit = body("news", "Changed title", Map.of());
            edit.put("version", original.path("version").asLong());
            edit.put("content", malformed);
            call(put("/api/v1/posts/" + id), edit)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("INVALID_CONTENT"));
            assertThat(data(call(get("/api/v1/posts/" + id)).andExpect(status().isOk())))
                    .isEqualTo(original);
            assertThat(data(mockMvc.perform(get("/api/v1/content/news/slug/keep-original"))
                    .andExpect(status().isOk()))).isEqualTo(originalPublic);
        }
    }

    @Test
    void draftsMayBeIncomplete() throws Exception {
        // No title, an empty body and none of the career's required fields: still saveable as a draft.
        Map<String, Object> bare = new HashMap<>();
        bare.put("type", "career");
        bare.put("content", doc(""));
        JsonNode created = data(call(post("/api/v1/posts"), bare).andExpect(status().isCreated()));
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("slug").asText()).startsWith("draft-");
        assertThat(created.path("fields").size()).isZero();
    }

    @Test
    void malformedFieldValuesAreRejectedEvenInADraft() throws Exception {
        Map<String, Object> fields = Map.of("applyUrl", "not a url", "employmentType", "freelance", "bogus", 1);
        call(post("/api/v1/posts"), body("career", "Bad", fields))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("fields.applyUrl")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("fields.employmentType")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("fields.bogus")));
    }

    @Test
    void typeIsRequiredKnownAndImmutable() throws Exception {
        Map<String, Object> noType = body("news", "No type", Map.of());
        noType.remove("type");
        call(post("/api/v1/posts"), noType).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));
        call(post("/api/v1/posts"), body("blog", "Wrong", Map.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNKNOWN_CONTENT_TYPE"));

        JsonNode news = create("news", "A news item", Map.of());
        Map<String, Object> retype = body("docs", "A news item", Map.of());
        retype.put("version", news.path("version").asLong());
        call(put("/api/v1/posts/" + news.path("id").asText()), retype).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("can't be changed")));
    }

    @Test
    void slugsAreUniqueWithinATypeOnly() throws Exception {
        create("news", "Launch", Map.of());
        create("docs", "Launch", VALID_FIELDS.get("docs")); // same slug, other type: fine
        call(post("/api/v1/posts"), body("news", "Launch", Map.of())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].errorCode").value("SLUG_TAKEN"));
    }

    @Test
    void aStaleVersionIsRefused() throws Exception {
        JsonNode created = create("news", "Racing", Map.of());
        Map<String, Object> edit = body("news", "Racing 2", Map.of());
        edit.put("version", created.path("version").asLong() + 5);
        call(put("/api/v1/posts/" + created.path("id").asText()), edit).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].errorCode").value("VERSION_CONFLICT"));
    }

    // ---- publishing rules

    @Test
    void invalidContentCannotBePublished() throws Exception {
        // Career without its required fields.
        String careerId = create("career", "Backend Intern", Map.of("location", "Berlin")).path("id").asText();
        call(post("/api/v1/posts/" + careerId + "/publish")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_PUBLISHABLE"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Department")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Employment type")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Application URL")));
        // The same for docs (section) and kb (symptom).
        for (String type : List.of("docs", "kb")) {
            String id = create(type, "Incomplete " + type, Map.of()).path("id").asText();
            call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_PUBLISHABLE"))
                    .andExpect(jsonPath("$.errors[0].message").value(containsString(type.equals("docs") ? "Section" : "Symptom")));
        }
        // Shared fields: a title and some content.
        Map<String, Object> empty = new HashMap<>();
        empty.put("type", "news");
        empty.put("content", doc(""));
        String newsId = data(call(post("/api/v1/posts"), empty).andExpect(status().isCreated())).path("id").asText();
        call(post("/api/v1/posts/" + newsId + "/publish")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("a title and some content")));
        // Nothing was published.
        mockMvc.perform(get("/api/v1/content/career")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void validContentOfEveryTypeCanBePublished() throws Exception {
        for (String type : List.of("news", "career", "docs", "kb")) {
            JsonNode published = createAndPublish(type, "Live " + type);
            assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(published.path("publishedAt").isNull()).isFalse();
        }
    }

    @Test
    void publishedContentMustStayPublishable() throws Exception {
        JsonNode live = createAndPublish("career", "Live career");
        Map<String, Object> edit = body("career", "Live career", Map.of("department", "Engineering"));
        edit.put("version", live.path("version").asLong());
        call(put("/api/v1/posts/" + live.path("id").asText()), edit).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_PUBLISHABLE"));
    }

    @Test
    void republishingKeepsTheFirstPublicationDate() throws Exception {
        JsonNode live = createAndPublish("news", "Twice");
        String id = live.path("id").asText();
        call(post("/api/v1/posts/" + id + "/unpublish")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        JsonNode again = data(call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isOk()));
        assertThat(again.path("publishedAt").asText()).isEqualTo(live.path("publishedAt").asText());
    }

    @Test
    void repeatedLifecycleOperationsAreIdempotent() throws Exception {
        JsonNode draft = create("news", "Lifecycle", Map.of());
        String draftId = draft.path("id").asText();
        JsonNode draftAgain = data(call(post("/api/v1/posts/" + draftId + "/unpublish")).andExpect(status().isOk()));
        assertThat(draftAgain.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draftAgain.path("version").asLong()).isEqualTo(draft.path("version").asLong());
        assertThat(draftAgain.path("updatedAt").asText()).isEqualTo(draft.path("updatedAt").asText());

        JsonNode published = createAndPublish("news", "Stable");
        String id = published.path("id").asText();
        JsonNode publishedAgain = data(call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isOk()));
        assertThat(publishedAgain.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(publishedAgain.path("version").asLong()).isEqualTo(published.path("version").asLong());
        assertThat(publishedAgain.path("updatedAt").asText()).isEqualTo(published.path("updatedAt").asText());
        assertThat(publishedAgain.path("publishedAt").asText()).isEqualTo(published.path("publishedAt").asText());

        JsonNode unpublished = data(call(post("/api/v1/posts/" + id + "/unpublish")).andExpect(status().isOk()));
        JsonNode unpublishedAgain = data(call(post("/api/v1/posts/" + id + "/unpublish")).andExpect(status().isOk()));
        assertThat(unpublishedAgain.path("status").asText()).isEqualTo("DRAFT");
        assertThat(unpublishedAgain.path("version").asLong()).isEqualTo(unpublished.path("version").asLong());
        assertThat(unpublishedAgain.path("updatedAt").asText()).isEqualTo(unpublished.path("updatedAt").asText());

        JsonNode archived = data(call(post("/api/v1/posts/" + id + "/archive")).andExpect(status().isOk()));
        JsonNode archivedAgain = data(call(post("/api/v1/posts/" + id + "/archive")).andExpect(status().isOk()));
        assertThat(archivedAgain.path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(archivedAgain.path("version").asLong()).isEqualTo(archived.path("version").asLong());
        assertThat(archivedAgain.path("updatedAt").asText()).isEqualTo(archived.path("updatedAt").asText());
    }

    // ---- public API

    @Test
    void publicApiOnlyReturnsPublishedContent() throws Exception {
        createAndPublish("news", "Visible");
        String draft = create("news", "Still a draft", Map.of()).path("id").asText();
        String archived = createAndPublish("news", "Archived later").path("id").asText();
        call(post("/api/v1/posts/" + archived + "/archive")).andExpect(status().isOk());
        String unpublished = createAndPublish("news", "Taken down").path("id").asText();
        call(post("/api/v1/posts/" + unpublished + "/unpublish")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/content/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slug").value("visible"));
        mockMvc.perform(get("/api/v1/content/news/slug/visible")).andExpect(status().isOk());
        for (String slug : List.of("still-a-draft", "archived-later", "taken-down", "does-not-exist")) {
            mockMvc.perform(get("/api/v1/content/news/slug/" + slug)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_FOUND"));
        }
        assertThat(draft).isNotBlank();
    }

    @Test
    void publicApiFiltersByType() throws Exception {
        for (String type : List.of("news", "career", "docs", "kb")) {
            createAndPublish(type, "Only " + type);
        }
        for (String type : List.of("news", "career", "docs", "kb")) {
            mockMvc.perform(get("/api/v1/content/" + type))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].type").value(type))
                    .andExpect(jsonPath("$.data[0].slug").value("only-" + type));
        }
        // A slug belongs to its type: the news item isn't reachable as docs.
        mockMvc.perform(get("/api/v1/content/docs/slug/only-news")).andExpect(status().isNotFound());
    }

    @Test
    void publicDetailCarriesTheBodyAndTypeFieldsButNoEditorData() throws Exception {
        createAndPublish("career", "Java Backend Intern");
        mockMvc.perform(get("/api/v1/content/career/slug/java-backend-intern"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Java Backend Intern"))
                .andExpect(jsonPath("$.data.fields.department").value("Engineering"))
                .andExpect(jsonPath("$.data.fields.remote").value(true))
                .andExpect(jsonPath("$.data.content.type").value("doc"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        mockMvc.perform(get("/api/v1/content/career"))
                .andExpect(jsonPath("$.data[0].fields.location").value("Mannheim"))
                .andExpect(jsonPath("$.data[0].content").doesNotExist());
    }

    @Test
    void publicListIsPaginatedAndSearchable() throws Exception {
        for (int i = 1; i <= 3; i++) {
            createAndPublish("news", "Item " + i);
        }
        mockMvc.perform(get("/api/v1/content/news?size=2&page=1&sort=title&direction=asc"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Item 3"))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mockMvc.perform(get("/api/v1/content/news").param("q", "item 2"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slug").value("item-2"));
        mockMvc.perform(get("/api/v1/content/news").param("q", "%")).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/content/news?sort=password")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/content/news").param("q", "\u0000")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownTypeIsNotFoundPublicly() throws Exception {
        mockMvc.perform(get("/api/v1/content/blog")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/content/blog/slug/x")).andExpect(status().isNotFound());
    }

    @Test
    void contentTypesAreDescribedPubliclyWithTheirDestination() throws Exception {
        mockMvc.perform(get("/api/v1/content-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].key").value("news"))
                .andExpect(jsonPath("$.data[0].destination.label").value("website"))
                .andExpect(jsonPath("$.data[0].destination.route").value("/news"))
                .andExpect(jsonPath("$.data[1].key").value("career"))
                .andExpect(jsonPath("$.data[1].destination.route").value("/careers"))
                .andExpect(jsonPath("$.data[1].fields[0].name").value("department"))
                .andExpect(jsonPath("$.data[1].fields[0].required").value(true))
                .andExpect(jsonPath("$.data[1].fields[2].type").value("select"))
                .andExpect(jsonPath("$.data[1].fields[2].options[0]").value("full-time"))
                .andExpect(jsonPath("$.data[2].key").value("docs"))
                .andExpect(jsonPath("$.data[2].destination.label").value("documentation"))
                .andExpect(jsonPath("$.data[2].destination.route").doesNotExist())
                .andExpect(jsonPath("$.data[3].key").value("kb"));
    }

    // ---- admin list and authentication

    @Test
    void adminListFiltersByTypeAndStatus() throws Exception {
        createAndPublish("news", "N1");
        create("news", "N2", Map.of());
        createAndPublish("kb", "K1");
        call(get("/api/v1/posts")).andExpect(jsonPath("$.meta.totalElements").value(3));
        call(get("/api/v1/posts?type=news")).andExpect(jsonPath("$.meta.totalElements").value(2));
        call(get("/api/v1/posts?type=news&status=DRAFT")).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("N2"));
        call(get("/api/v1/posts?type=kb")).andExpect(jsonPath("$.data[0].type").value("kb"));
        call(get("/api/v1/posts?type=bogus")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNKNOWN_CONTENT_TYPE"));
        call(get("/api/v1/posts?status=BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("status")))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.not(containsString("PostStatus"))));
    }

    @Test
    void invalidPaginationParametersReturnSafeBadRequestErrors() throws Exception {
        call(get("/api/v1/posts?page=not-a-number&size=also-not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("page")))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("size")))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.not(containsString("Integer"))));

        mockMvc.perform(get("/api/v1/content/news?page=not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("page")))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.not(containsString("Integer"))));

        mockMvc.perform(get("/api/v1/content/news?size=not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("size")))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.not(containsString("Integer"))));
    }

    @Test
    void adminRoutesNeedASignedInAdminButPublicOnesDoNot() throws Exception {
        JsonNode live = createAndPublish("news", "Public one");
        String id = live.path("id").asText();
        mockMvc.perform(get("/api/v1/posts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/posts/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/posts").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/posts/" + id).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for (String action : List.of("publish", "unpublish", "archive")) {
            mockMvc.perform(post("/api/v1/posts/" + id + "/" + action)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/content-types")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/content/news")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/content/news/slug/public-one")).andExpect(status().isOk());
        // HEAD must follow the same public rules as GET (caches / probes).
        mockMvc.perform(head("/api/v1/content-types")).andExpect(status().isOk());
        mockMvc.perform(head("/api/v1/content/news")).andExpect(status().isOk());
        mockMvc.perform(head("/api/v1/content/news/slug/public-one")).andExpect(status().isOk());
        mockMvc.perform(head("/api/v1/posts")).andExpect(status().isUnauthorized());
        // Public does not mean writable.
        mockMvc.perform(post("/api/v1/content/news")).andExpect(status().isUnauthorized());
    }

    // ---- docs custom ordering (Issue #27)

    private JsonNode createAndPublishDoc(String title, String slug, Integer order) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("section", "Guides");
        if (order != null) {
            fields.put("order", order);
        }
        Map<String, Object> payload = body("docs", title, fields);
        if (slug != null) {
            payload.put("slug", slug);
        }
        JsonNode created = data(call(post("/api/v1/posts"), payload).andExpect(status().isCreated()));
        return data(call(post("/api/v1/posts/" + created.path("id").asText() + "/publish")).andExpect(status().isOk()));
    }

    @Test
    void docsSortByOrderAscendingWithNullsLastAndDeterministicTieBreaking() throws Exception {
        // Numeric ordering check: 2 must come before 10 (not lexicographical "10" < "2")
        createAndPublishDoc("Ten", "ten", 10);
        createAndPublishDoc("One", "one", 1);
        createAndPublishDoc("Two", "two", 2);
        // Ties on order=2 broken by title ASC
        createAndPublishDoc("Alpha Tie", "alpha-tie", 2);
        // Same order and same title broken by id ASC
        JsonNode sameTitleA = createAndPublishDoc("Same Title", "same-title-a", 5);
        JsonNode sameTitleB = createAndPublishDoc("Same Title", "same-title-b", 5);
        List<String> expectedSameTitleIds = List.of(
                sameTitleA.path("id").asText(),
                sameTitleB.path("id").asText()
        ).stream().sorted().toList();
        // Missing order values must appear last, also broken by title ASC
        createAndPublishDoc("Unordered Zebra", "unordered-zebra", null);
        createAndPublishDoc("Unordered Alpha", "unordered-alpha", null);

        mockMvc.perform(get("/api/v1/content/docs?sort=order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].slug").value("one"))
                .andExpect(jsonPath("$.data[1].slug").value("alpha-tie"))
                .andExpect(jsonPath("$.data[2].slug").value("two"))
                .andExpect(jsonPath("$.data[3].id").value(expectedSameTitleIds.get(0)))
                .andExpect(jsonPath("$.data[4].id").value(expectedSameTitleIds.get(1)))
                .andExpect(jsonPath("$.data[5].slug").value("ten"))
                .andExpect(jsonPath("$.data[6].slug").value("unordered-alpha"))
                .andExpect(jsonPath("$.data[7].slug").value("unordered-zebra"));
    }

    @Test
    void docsSortByOrderWorksAcrossPaginationBoundaries() throws Exception {
        createAndPublishDoc("Doc 3", "doc-3", 3);
        createAndPublishDoc("Doc 1", "doc-1", 1);
        createAndPublishDoc("Doc Unordered", "doc-unordered", null);
        createAndPublishDoc("Doc 2", "doc-2", 2);

        mockMvc.perform(get("/api/v1/content/docs?sort=order&size=2&page=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].slug").value("doc-1"))
                .andExpect(jsonPath("$.data[1].slug").value("doc-2"))
                .andExpect(jsonPath("$.meta.totalElements").value(4))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

        mockMvc.perform(get("/api/v1/content/docs?sort=order&size=2&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].slug").value("doc-3"))
                .andExpect(jsonPath("$.data[1].slug").value("doc-unordered"));
    }

    @Test
    void sortByOrderIsRejectedForNonDocsTypesAndDescDirection() throws Exception {
        for (String type : List.of("news", "career", "kb")) {
            mockMvc.perform(get("/api/v1/content/" + type + "?sort=order"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"));
        }
        mockMvc.perform(get("/api/v1/content/docs?sort=order&direction=desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"));
    }

    @Test
    void docsDefaultSortingRemainsUnchangedWhenSortIsOmitted() throws Exception {
        createAndPublishDoc("First Published", "first-published", 1);
        createAndPublishDoc("Second Published", "second-published", 10);

        // Default is publishedAt DESC, so Second Published comes first despite higher order
        mockMvc.perform(get("/api/v1/content/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].slug").value("second-published"))
                .andExpect(jsonPath("$.data[1].slug").value("first-published"));
    }
}
