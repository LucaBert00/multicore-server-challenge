package conduit;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Articles, the feed, and favorites. */
@RestController
class ArticleController {

    record ArticleList(List<Dto.Article> articles, long articlesCount) {
    }

    private record Owner(long id, long authorId) {
    }

    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final Body body;

    ArticleController(NamedParameterJdbcTemplate db, TransactionTemplate tx, Body body) {
        this.db = db;
        this.tx = tx;
        this.body = body;
    }

    /** limit (default 20, 1..100) and offset (default 0, >= 0); a parameter that is present, even empty, must be valid. */
    static Sql.Params page(Sql.Params p, String limit, String offset) {
        long l = limit == null ? 20 : Body.parseUint(limit);
        long o = offset == null ? 0 : Body.parseUint(offset);
        if (l < 1 || l > 100 || o < 0) {
            throw ApiException.invalid("limit must be 1-100 and offset >= 0");
        }
        return p.bigint("limit", l).bigint("offset", o);
    }

    private static boolean given(String s) {
        return s != null && !s.isEmpty();
    }

    @GetMapping("/api/articles")
    ArticleList list(Auth.Viewer viewer,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String favorited,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset) {
        Sql.Params p = page(Sql.params().bigint("viewer", viewer.id()), limit, offset);
        String pageSql;
        String countSql;
        if (given(tag)) {
            pageSql = Sql.LIST_TAG;
            countSql = Sql.COUNT_TAG;
            p.text("filter", tag);
        } else if (given(author)) {
            pageSql = Sql.LIST_AUTHOR;
            countSql = Sql.COUNT_AUTHOR;
            p.text("filter", author);
        } else if (given(favorited)) {
            pageSql = Sql.LIST_FAVORITED;
            countSql = Sql.COUNT_FAVORITED;
            p.text("filter", favorited);
        } else {
            pageSql = Sql.LIST_ALL;
            countSql = Sql.COUNT_ALL;
        }
        List<Dto.Article> articles = db.query(pageSql, p, Sql.ARTICLE_LIST_ROW);
        Long count = db.queryForObject(countSql, p, Long.class);
        return new ArticleList(articles, count);
    }

    @GetMapping("/api/articles/feed")
    ArticleList feed(Auth.User auth,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset) {
        Sql.Params p = page(Sql.params().bigint("viewer", auth.id()), limit, offset);
        List<Dto.Article> articles = db.query(Sql.LIST_FEED, p, Sql.ARTICLE_LIST_ROW);
        Long count = db.queryForObject(Sql.COUNT_FEED, p, Long.class);
        return new ArticleList(articles, count);
    }

    @GetMapping("/api/articles/{slug}")
    Map<String, Dto.Article> get(Auth.Viewer viewer, @PathVariable String slug) {
        List<Dto.Article> rows = db.query(Sql.ARTICLE_BY_SLUG,
                Sql.params().bigint("viewer", viewer.id()).text("slug", slug), Sql.ARTICLE_FULL_ROW);
        if (rows.isEmpty()) {
            throw ApiException.notFound("article");
        }
        return Map.of("article", rows.get(0));
    }

    @PostMapping("/api/articles")
    ResponseEntity<Map<String, Dto.Article>> create(Auth.User auth, HttpServletRequest request) {
        JsonNode article = body.object(request, "article");
        String title = Body.required(article, "title");
        String description = Body.required(article, "description");
        String text = Body.required(article, "body");
        List<String> tags = Body.stringList(article, "tagList");

        Long id = tx.execute(status -> {
            Long newId = db.queryForObject(Sql.ARTICLE_INSERT, Sql.params().bigint("viewer", auth.id())
                    .text("title", title).text("description", description).text("body", text), Long.class);
            if (!tags.isEmpty()) {
                db.update(Sql.TAGS_INSERT, Sql.params().textArray("tags", tags));
                db.update(Sql.ARTICLE_TAGS_INSERT, Sql.params().bigint("id", newId).textArray("tags", tags));
            }
            return newId;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("article", byId(auth.id(), id)));
    }

    @PutMapping("/api/articles/{slug}")
    Map<String, Dto.Article> update(Auth.User auth, @PathVariable String slug, HttpServletRequest request) {
        JsonNode article = body.object(request, "article");
        String title = Body.optionalNonEmpty(article, "title");
        String description = Body.optionalNonEmpty(article, "description");
        String text = Body.optionalNonEmpty(article, "body");
        Owner owner = owner(auth, slug);
        db.update(Sql.ARTICLE_UPDATE, Sql.params().bigint("id", owner.id())
                .text("title", title).text("description", description).text("body", text));
        return Map.of("article", byId(auth.id(), owner.id()));
    }

    @DeleteMapping("/api/articles/{slug}")
    ResponseEntity<Void> delete(Auth.User auth, @PathVariable String slug) {
        Owner owner = owner(auth, slug);
        db.update(Sql.ARTICLE_DELETE, Sql.params().bigint("id", owner.id()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/articles/{slug}/favorite")
    Map<String, Dto.Article> favorite(Auth.User auth, @PathVariable String slug) {
        return setFavorite(auth, slug, true);
    }

    @DeleteMapping("/api/articles/{slug}/favorite")
    Map<String, Dto.Article> unfavorite(Auth.User auth, @PathVariable String slug) {
        return setFavorite(auth, slug, false);
    }

    private Map<String, Dto.Article> setFavorite(Auth.User auth, String slug, boolean favorite) {
        long id = articleId(db, slug);
        db.update(favorite ? Sql.FAVORITE : Sql.UNFAVORITE, Sql.params().bigint("viewer", auth.id()).bigint("id", id));
        return Map.of("article", byId(auth.id(), id));
    }

    /** SELECT id, author_id FROM articles WHERE slug = $1: 404 if none, 403 if not the viewer's. */
    private Owner owner(Auth.User auth, String slug) {
        List<Owner> rows = db.query(Sql.ARTICLE_OWNER, Sql.params().text("slug", slug),
                (rs, n) -> new Owner(rs.getLong("id"), rs.getLong("author_id")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("article");
        }
        if (rows.get(0).authorId() != auth.id()) {
            throw ApiException.forbidden();
        }
        return rows.get(0);
    }

    private Dto.Article byId(long viewer, long id) {
        List<Dto.Article> rows = db.query(Sql.ARTICLE_BY_ID, Sql.params().bigint("viewer", viewer).bigint("id", id),
                Sql.ARTICLE_FULL_ROW);
        if (rows.isEmpty()) {
            throw ApiException.notFound("article");
        }
        return rows.get(0);
    }

    /** SELECT id FROM articles WHERE slug = $1, 404 if none. */
    static long articleId(NamedParameterJdbcTemplate db, String slug) {
        List<Long> rows = db.queryForList(Sql.ARTICLE_ID_BY_SLUG, Sql.params().text("slug", slug), Long.class);
        if (rows.isEmpty()) {
            throw ApiException.notFound("article");
        }
        return rows.get(0);
    }
}
