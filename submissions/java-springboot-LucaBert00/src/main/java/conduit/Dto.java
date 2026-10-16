package conduit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire objects (SPEC.md "Objects"). Records serialize their components by name; nulls are written as null. */
final class Dto {

    private Dto() {
    }

    record User(String email, String token, String username, String bio, String image) {
    }

    record Profile(String username, String bio, String image, boolean following) {
    }

    /** List endpoints leave body null, and it is then omitted (the column itself is NOT NULL). */
    record Article(String slug, String title, String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) String body,
            List<String> tagList, String createdAt, String updatedAt,
            boolean favorited, long favoritesCount, Profile author) {
    }

    record Comment(long id, String createdAt, String updatedAt, String body, Profile author) {
    }

    record Errors(Map<String, List<String>> errors) {
        static Errors of(String message) {
            return new Errors(Map.of("body", List.of(message)));
        }
    }

    /** RFC 3339 UTC with exactly 3 fractional digits; SSS truncates the microseconds. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static String ts(OffsetDateTime t) {
        return TS.format(t);
    }
}
