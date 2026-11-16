package io.atomity.generalcms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.atomity.generalcms.shared.ApiException;
import io.atomity.generalcms.shared.NulCharacters;

import java.net.URI;
import java.net.IDN;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Checks and normalises the type-specific {@code fields} of an item. Two separate questions:
 * <ul>
 *   <li>{@link #normalize}: is what was sent well-formed? Runs on every save, so a draft can't hold
 *       garbage (a malformed URL, an unknown field) but may leave any field empty.</li>
 *   <li>{@link #missingRequired}: is everything a published item needs there? Runs on publish only.</li>
 * </ul>
 */
public final class FieldValidator {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_NUMBER = 1_000_000;

    private FieldValidator() {
    }

    /**
     * Returns the cleaned fields: only known names, texts stripped, empty values dropped.
     *
     * @throws ApiException 400 {@code VALIDATION_ERROR} listing every problem found
     */
    public static ObjectNode normalize(ContentTypeDefinition type, JsonNode input) {
        ObjectNode cleaned = JsonNodeFactory.instance.objectNode();
        if (input == null || input.isNull()) {
            return cleaned;
        }
        if (!input.isObject()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "fields must be an object");
        }
        List<String> problems = new ArrayList<>();

        Set<String> known = new HashSet<>();
        type.fields().forEach(field -> known.add(field.name()));
        for (Iterator<String> names = input.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                problems.add("fields." + name + " is not a field of the \"" + type.key() + "\" type");
            }
        }

        for (FieldDefinition field : type.fields()) {
            JsonNode value = input.get(field.name());
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                continue;
            }
            String problem = check(field, value, cleaned);
            if (problem != null) {
                problems.add("fields." + field.name() + " " + problem);
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", String.join("; ", problems));
        }
        return cleaned;
    }

    /** Labels of the required fields that are still empty, in definition order. */
    public static List<String> missingRequired(ContentTypeDefinition type, JsonNode fields) {
        List<String> missing = new ArrayList<>();
        for (FieldDefinition field : type.fields()) {
            if (field.required() && (fields == null || !fields.hasNonNull(field.name()))) {
                missing.add(field.label());
            }
        }
        return missing;
    }

    /** Adds the cleaned value to {@code out} and returns null, or returns what is wrong with it. */
    private static String check(FieldDefinition field, JsonNode value, ObjectNode out) {
        String name = field.name();
        switch (field.type()) {
            case TEXT -> {
                if (!value.isTextual()) return "must be text";
                String text = value.asText().strip();
                if (NulCharacters.foundIn(text)) return NulCharacters.MESSAGE;
                if (text.length() > MAX_TEXT_LENGTH) return "must be at most " + MAX_TEXT_LENGTH + " characters";
                out.put(name, text);
            }
            case URL -> {
                if (!value.isTextual()) return "must be text";
                String text = value.asText().strip();
                if (text.length() > MAX_TEXT_LENGTH) return "must be at most " + MAX_TEXT_LENGTH + " characters";
                if (!isHttpUrl(text)) return "must be a valid URL starting with http:// or https://";
                out.put(name, text);
            }
            case DATE -> {
                if (!value.isTextual()) return "must be a date like 2026-12-31";
                try {
                    out.put(name, LocalDate.parse(value.asText().strip()).toString());
                } catch (DateTimeParseException e) {
                    return "must be a date like 2026-12-31";
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) return "must be true or false";
                out.put(name, value.asBoolean());
            }
            case NUMBER -> {
                if (!value.isIntegralNumber() || !value.canConvertToInt()
                        || value.asInt() < 0 || value.asInt() > MAX_NUMBER) {
                    return "must be a whole number between 0 and " + MAX_NUMBER;
                }
                out.put(name, value.asInt());
            }
            case SELECT -> {
                String text = value.isTextual() ? value.asText().strip() : null;
                if (text == null || !field.options().contains(text)) {
                    return "must be one of: " + String.join(", ", field.options());
                }
                out.put(name, text);
            }
        }
        return null;
    }

    private static boolean isHttpUrl(String text) {
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;

            String host = uri.getHost();
            if (host != null && !host.isBlank()) return true;

            // URI#getHost returns null for valid Unicode domain names. Convert just the hostname
            // to ASCII for validation while leaving the submitted URL untouched.
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) return false;
            int userInfoEnd = authority.lastIndexOf('@');
            String hostAndPort = authority.substring(userInfoEnd + 1);
            if (hostAndPort.startsWith("[")) return false; // IPv6 should already have parsed above.

            int portStart = hostAndPort.lastIndexOf(':');
            String unicodeHost = portStart >= 0 ? hostAndPort.substring(0, portStart) : hostAndPort;
            String port = portStart >= 0 ? hostAndPort.substring(portStart + 1) : null;
            String asciiHost = IDN.toASCII(unicodeHost, IDN.USE_STD3_ASCII_RULES);
            if (asciiHost.isBlank()) return false;

            // Re-parse the authority after IDN conversion so malformed ports and authority
            // syntax are rejected by the same URI parser used for ordinary URLs.
            String asciiAuthority = authority.substring(0, userInfoEnd + 1) + asciiHost
                    + (port == null ? "" : ":" + port);
            URI normalized = new URI(scheme + "://" + asciiAuthority + uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
            return normalized.getHost() != null && !normalized.getHost().isBlank();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false;
        }
    }
}
