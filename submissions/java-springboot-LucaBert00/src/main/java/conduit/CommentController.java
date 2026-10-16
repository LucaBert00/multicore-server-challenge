package conduit;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CommentController {

    private final NamedParameterJdbcTemplate db;
    private final Body body;

    CommentController(NamedParameterJdbcTemplate db, Body body) {
        this.db = db;
        this.body = body;
    }

    @GetMapping("/api/articles/{slug}/comments")
    Map<String, List<Dto.Comment>> list(Auth.Viewer viewer, @PathVariable String slug) {
        long id = ArticleController.articleId(db, slug);
        return Map.of("comments",
                db.query(Sql.COMMENTS, Sql.params().bigint("viewer", viewer.id()).bigint("id", id), Sql.COMMENT_ROW));
    }

    @PostMapping("/api/articles/{slug}/comments")
    ResponseEntity<Map<String, Dto.Comment>> create(Auth.User auth, @PathVariable String slug,
            HttpServletRequest request) {
        JsonNode comment = body.object(request, "comment");
        String text = Body.required(comment, "body");
        long id = ArticleController.articleId(db, slug);
        Dto.Comment c = db.queryForObject(Sql.COMMENT_INSERT,
                Sql.params().bigint("id", id).bigint("viewer", auth.id()).text("body", text), Sql.NEW_COMMENT_ROW);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("comment", c));
    }

    @DeleteMapping("/api/articles/{slug}/comments/{id}")
    ResponseEntity<Void> delete(Auth.User auth, @PathVariable String slug, @PathVariable String id) {
        long commentId = Body.parseUint(id);
        if (commentId < 0) {
            throw ApiException.notFound("comment");
        }
        List<Long> rows = db.queryForList(Sql.COMMENT_OWNER,
                Sql.params().bigint("id", commentId).text("slug", slug), Long.class);
        if (rows.isEmpty()) {
            throw ApiException.notFound("comment");
        }
        if (rows.get(0) != auth.id()) {
            throw ApiException.forbidden();
        }
        db.update(Sql.COMMENT_DELETE, Sql.params().bigint("id", commentId));
        return ResponseEntity.noContent().build();
    }
}
