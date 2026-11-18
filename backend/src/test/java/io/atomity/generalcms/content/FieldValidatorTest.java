package io.atomity.generalcms.content;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.atomity.generalcms.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldValidatorTest {

    private static final ContentTypeDefinition URL_TYPE = new ContentTypeDefinition(
        "event",
        "Event",
        "Test type with a URL field",
        new ContentTypeDefinition.Destination("website", "/events"),
        List.of(FieldDefinition.url("website", "Website", false))
    );

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com",
        "http://example.com:8080/path?q=1",
        "https://münchen.de/events",
        "https://bücher.example:8443/shop",
        "https://日本語.jp/test",
        "https://xn--mnchen-3ya.de/events"
    })
    void normalizeAcceptsValidAsciiAndIdnUrls(String url) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("website", url);

        ObjectNode cleaned = FieldValidator.normalize(URL_TYPE, input);

        assertEquals(url, cleaned.get("website").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "münchen.de",
        "ftp://münchen.de/events",
        "https://",
        "https://:8080/path",
        "https://münchen.de:99999/events",
        "https://münchen.de:abc/events",
        "https://-invalid-.de/events",
        "https://exa mple.com"
    })
    void normalizeRejectsInvalidUrls(String invalidUrl) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("website", invalidUrl);

        assertThrows(ApiException.class, () -> FieldValidator.normalize(URL_TYPE, input));
    }

    @Test
    void normalizePreservesOriginalUnicodeUrlAfterStrippingWhitespace() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("website", "  https://münchen.de/events  ");

        ObjectNode cleaned = FieldValidator.normalize(URL_TYPE, input);

        assertEquals("https://münchen.de/events", cleaned.get("website").asText());
    }
}
