package conduit;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TagController {

    private final JdbcTemplate db;

    TagController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/api/tags")
    Map<String, List<String>> tags() {
        return Map.of("tags", db.queryForList(Sql.TAGS, String.class));
    }
}
