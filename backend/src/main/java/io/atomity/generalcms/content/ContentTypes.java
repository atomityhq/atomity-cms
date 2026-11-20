package io.atomity.generalcms.content;

import io.atomity.generalcms.content.ContentTypeDefinition.Destination;
import io.atomity.generalcms.shared.ApiException;

import java.util.List;
import java.util.Optional;

import static io.atomity.generalcms.content.FieldDefinition.bool;
import static io.atomity.generalcms.content.FieldDefinition.date;
import static io.atomity.generalcms.content.FieldDefinition.number;
import static io.atomity.generalcms.content.FieldDefinition.select;
import static io.atomity.generalcms.content.FieldDefinition.text;
import static io.atomity.generalcms.content.FieldDefinition.url;

/**
 * The content-type registry: a plain, explicit list. To add a type, add an entry here (see
 * "Adding a content type" in the README); the database, the API and the admin form pick it up
 * from this list, so no migration and no new endpoint is needed.
 */
public final class ContentTypes {

    public static final String NEWS = "news";
    public static final String CAREER = "career";
    public static final String DOCS = "docs";
    public static final String KB = "kb";
    public static final String EVENT = "event";

    private static final List<ContentTypeDefinition> ALL = List.of(
            new ContentTypeDefinition(NEWS, "News",
                    "Company news, announcements and updates.",
                    new Destination("website", "/news"),
                    List.of()),
            new ContentTypeDefinition(CAREER, "Career opening",
                    "Job openings, internships and other career opportunities.",
                    new Destination("website", "/careers"),
                    List.of(
                            text("department", "Department", true),
                            text("location", "Location", true),
                            select("employmentType", "Employment type", true,
                                    List.of("full-time", "part-time", "intern", "contract")),
                            url("applyUrl", "Application URL", true),
                            date("closingDate", "Closing date", false),
                            bool("remote", "Remote"))),
            new ContentTypeDefinition(DOCS, "Documentation",
                    "Product documentation, technical guides and onboarding instructions.",
                    new Destination("documentation", null),
                    List.of(
                            text("section", "Section", true),
                            number("order", "Order", false))),
            new ContentTypeDefinition(KB, "Knowledge base",
                    "Troubleshooting articles, FAQs, configuration guidance and common errors with their resolutions.",
                    new Destination("documentation", null),
                    List.of(
                            text("symptom", "Symptom", true),
                            text("product", "Product", false),
                            text("affectedVersions", "Affected versions", false))),
            new ContentTypeDefinition(EVENT, "Event",
                    "Meetups, webinars and conferences.",
                    new Destination("website", "/events"),
                    List.of(
                            text("venue", "Venue", true),
                            date("startsOn", "Starts on", true),
                            url("registrationUrl", "Registration URL", false),
                            bool("online", "Online"))));

    private ContentTypes() {
    }

    public static List<ContentTypeDefinition> all() {
        return ALL;
    }

    public static Optional<ContentTypeDefinition> find(String key) {
        return ALL.stream().filter(type -> type.key().equals(key)).findFirst();
    }

    /** For admin input: an unknown type is a client error (400). */
    public static ContentTypeDefinition require(String key) {
        return find(key).orElseThrow(() -> ApiException.badRequest("UNKNOWN_CONTENT_TYPE",
                "Unknown content type \"" + key + "\". Use one of: " + String.join(", ", keys()) + "."));
    }

    public static List<String> keys() {
        return ALL.stream().map(ContentTypeDefinition::key).toList();
    }
}
