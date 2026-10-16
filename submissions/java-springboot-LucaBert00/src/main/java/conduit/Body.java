package conduit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * Request bodies are read as a JSON tree whatever the Content-Type, after auth has run. Strings must be
 * JSON strings (Jackson's data binding would coerce {"body": 123} to "123"). Anything malformed is a 422.
 */
@Component
class Body {

    private final JsonMapper mapper;

    Body(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The object under {@code key} in the request body; 422 if the body isn't {"key": {...}}. */
    JsonNode object(HttpServletRequest request, String key) {
        JsonNode root;
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            root = mapper.readTree(bytes);
        } catch (IOException | JacksonException ex) {
            throw ApiException.invalid("malformed JSON body");
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw ApiException.invalid("malformed JSON body");
        }
        if (!root.isObject()) {
            throw ApiException.invalid("body must be a JSON object");
        }
        JsonNode inner = root.get(key);
        if (inner == null || !inner.isObject()) {
            throw ApiException.invalid(key + " must be an object");
        }
        return inner;
    }

    /** A required non-empty string field. */
    static String required(JsonNode obj, String field) {
        String s = optional(obj, field);
        if (s == null || s.isEmpty()) {
            throw ApiException.invalid(field + " can't be blank");
        }
        return s;
    }

    /** An optional non-empty string field: null when absent or JSON null; 422 when empty or not a string. */
    static String optionalNonEmpty(JsonNode obj, String field) {
        String s = optional(obj, field);
        if (s != null && s.isEmpty()) {
            throw ApiException.invalid(field + " can't be blank");
        }
        return s;
    }

    /** An optional string field: null when absent or JSON null; 422 when it isn't a string. */
    static String optional(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw ApiException.invalid(field + " must be a string");
        }
        return n.stringValue();
    }

    /** An optional array of non-empty strings: empty when absent or null. */
    static List<String> stringList(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw ApiException.invalid(field + " must be an array");
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode e : n) {
            if (!e.isString() || e.stringValue().isEmpty()) {
                throw ApiException.invalid(field + " must contain non-empty strings");
            }
            out.add(e.stringValue());
        }
        return out;
    }

    /** bcrypt only hashes the first 72 bytes; Spring's encoder (like Go's) refuses longer passwords: 422. */
    static String hashable(String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw ApiException.invalid("password is too long");
        }
        return password;
    }

    /** ASCII digits only (no sign, no spaces) that fit in a long; -1 otherwise. */
    static long parseUint(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
