package io.atomity.generalcms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atomity.generalcms.shared.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The registry and the field validator, without Spring or a database. */
class ContentTypesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static ContentTypeDefinition career() {
        return ContentTypes.require(ContentTypes.CAREER);
    }

    @Test
    void registryHoldsTheFourTypesWithTheirDestinations() {
        assertThat(ContentTypes.keys()).containsExactly("news", "career", "docs", "kb", "event");
        assertThat(ContentTypes.require("news").destination()).isEqualTo(new ContentTypeDefinition.Destination("website", "/news"));
        assertThat(ContentTypes.require("career").destination()).isEqualTo(new ContentTypeDefinition.Destination("website", "/careers"));
        assertThat(ContentTypes.require("docs").destination().label()).isEqualTo("documentation");
        assertThat(ContentTypes.require("docs").destination().route()).isNull();
        assertThat(ContentTypes.require("kb").destination().route()).isNull();
        assertThat(ContentTypes.require("event").destination()).isEqualTo(new ContentTypeDefinition.Destination("website", "/events"));
    }

    @Test
    void careerRequiresFourFieldsAndKeepsTwoOptional() {
        List<String> required = career().fields().stream().filter(FieldDefinition::required).map(FieldDefinition::name).toList();
        assertThat(required).containsExactly("department", "location", "employmentType", "applyUrl");
        assertThat(career().field("closingDate").required()).isFalse();
        assertThat(career().field("remote").required()).isFalse();
        assertThat(career().field("employmentType").options()).containsExactly("full-time", "part-time", "intern", "contract");
    }

    @Test
    void unknownTypeIsRejectedWithAClearCode() {
        assertThatThrownBy(() -> ContentTypes.require("blog"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("UNKNOWN_CONTENT_TYPE");
                    assertThat(e.getMessage()).contains("news, career, docs, kb, event");
                });
    }

    @Test
    void normalizeStripsTextAndDropsEmptyValues() throws Exception {
        var cleaned = FieldValidator.normalize(career(), json("""
                {"department": "  Engineering ", "location": "", "applyUrl": null, "remote": false}"""));
        assertThat(cleaned.toString()).isEqualTo("{\"department\":\"Engineering\",\"remote\":false}");
    }

    @Test
    void normalizeAcceptsAFullyValidCareer() throws Exception {
        var cleaned = FieldValidator.normalize(career(), json("""
                {"department":"Engineering","location":"Mannheim","employmentType":"intern",
                 "applyUrl":"https://example.org/apply","closingDate":"2026-12-31","remote":true}"""));
        assertThat(cleaned.size()).isEqualTo(6);
        assertThat(FieldValidator.missingRequired(career(), cleaned)).isEmpty();
    }

    @Test
    void normalizeReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> FieldValidator.normalize(career(), json("""
                {"applyUrl":"javascript:alert(1)","employmentType":"freelance","closingDate":"31.12.2026",
                 "remote":"yes","bogus":1}""")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getMessage())
                            .contains("fields.bogus is not a field of the \"career\" type")
                            .contains("fields.applyUrl must be a valid URL starting with http:// or https://")
                            .contains("fields.employmentType must be one of: full-time, part-time, intern, contract")
                            .contains("fields.closingDate must be a date like 2026-12-31")
                            .contains("fields.remote must be true or false");
                });
    }

    @Test
    void urlsMustBeHttpWithAHost() throws Exception {
        for (String bad : new String[] {"example.org/apply", "ftp://example.org", "http://", "https://exa mple.org", "mailto:a@b.de"}) {
            assertThatThrownBy(() -> FieldValidator.normalize(career(), json("{\"applyUrl\":\"" + bad + "\"}")))
                    .as(bad).isInstanceOf(ApiException.class);
        }
        assertThat(FieldValidator.normalize(career(), json("{\"applyUrl\":\"http://localhost:3000/x?y=1\"}")).size()).isEqualTo(1);
    }

    @Test
    void numbersMustBeWholeAndNotNegative() throws Exception {
        var docs = ContentTypes.require("docs");
        assertThat(FieldValidator.normalize(docs, json("{\"order\": 3}")).get("order").asInt()).isEqualTo(3);
        for (String bad : new String[] {"-1", "1.5", "\"3\"", "9999999999"}) {
            assertThatThrownBy(() -> FieldValidator.normalize(docs, json("{\"order\":" + bad + "}"))).as(bad).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void fieldsMustBeAnObject() throws Exception {
        assertThatThrownBy(() -> FieldValidator.normalize(career(), json("[1]"))).isInstanceOf(ApiException.class);
        assertThat(FieldValidator.normalize(career(), null).size()).isZero();
    }

    @Test
    void missingRequiredListsLabelsInDefinitionOrder() throws Exception {
        assertThat(FieldValidator.missingRequired(career(), json("{\"location\":\"Berlin\"}")))
                .containsExactly("Department", "Employment type", "Application URL");
        assertThat(FieldValidator.missingRequired(ContentTypes.require("news"), json("{}"))).isEmpty();
        assertThat(FieldValidator.missingRequired(ContentTypes.require("kb"), json("{\"product\":\"x\"}"))).containsExactly("Symptom");
    }
    @Test
    void eventRequiresVenueAndStartsOnAndKeepsRegistrationUrlAndOnlineOptional() throws Exception {
        var eventType = ContentTypes.require(ContentTypes.EVENT);
        List<String> required = eventType.fields().stream()
                .filter(FieldDefinition::required)
                .map(FieldDefinition::name)
                .toList();
        assertThat(required).containsExactly("venue", "startsOn");
        assertThat(eventType.field("registrationUrl").required()).isFalse();
        assertThat(eventType.field("online").required()).isFalse();

        var cleaned = FieldValidator.normalize(eventType, json("""
                {"venue":"  Baku Tech Hub ","startsOn":"2026-12-10","registrationUrl":"https://example.org/event","online":true}"""));
        assertThat(cleaned.get("venue").asText()).isEqualTo("Baku Tech Hub");
        assertThat(FieldValidator.missingRequired(eventType, cleaned)).isEmpty();
    }
}
