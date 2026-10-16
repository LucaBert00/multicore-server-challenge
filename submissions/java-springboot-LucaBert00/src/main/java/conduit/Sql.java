package conduit;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.SqlValue;

import java.sql.PreparedStatement;

/**
 * The SQL from SPEC.md, verbatim; {@code $n} placeholders become named parameters
 * (NamedParameterJdbcTemplate turns each into a JDBC {@code ?}). Every parameter is bound with an
 * explicit SQL type (viewer and ids as BIGINT, strings as VARCHAR) so a null never costs a describe round trip.
 */
final class Sql {

    private Sql() {
    }

    // ---- shared fragments ($1 = viewer) ----

    static final String ARTICLE_LIST_SELECT = """
            SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at,
                   ARRAY(SELECT t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
                          WHERE at.article_id = a.id ORDER BY t.name) AS tag_list,
                   (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
                   EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
                   u.username, u.bio, u.image,
                   EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
              FROM articles a
              JOIN users u ON u.id = a.author_id
            """;

    static final String ARTICLE_FULL_SELECT = """
            SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at,
                   ARRAY(SELECT t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
                          WHERE at.article_id = a.id ORDER BY t.name) AS tag_list,
                   (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
                   EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
                   u.username, u.bio, u.image,
                   EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
              FROM articles a
              JOIN users u ON u.id = a.author_id
            """;

    static final String ORDER_PAGE = " ORDER BY a.id DESC LIMIT :limit OFFSET :offset";

    // ---- users ----

    static final String USER_INSERT = """
            INSERT INTO users (username, email, password_hash) VALUES (:username, :email, :hash)
            RETURNING id, username, email, bio, image""";

    static final String USER_BY_EMAIL =
            "SELECT id, username, email, bio, image, password_hash FROM users WHERE email = :email";

    static final String USER_BY_ID = "SELECT id, username, email, bio, image FROM users WHERE id = :id";

    static final String USER_UPDATE = """
            UPDATE users SET email = COALESCE(:email, email), username = COALESCE(:username, username),
                             password_hash = COALESCE(:hash, password_hash), bio = COALESCE(:bio, bio), image = COALESCE(:image, image)
             WHERE id = :id
            RETURNING id, username, email, bio, image""";

    // ---- profiles ----

    static final String PROFILE = """
            SELECT u.id, u.username, u.bio, u.image,
                   EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = u.id) AS following
              FROM users u WHERE u.username = :username""";

    static final String USER_BY_USERNAME = "SELECT id, username, bio, image FROM users WHERE username = :username";

    static final String FOLLOW =
            "INSERT INTO follows (follower_id, followee_id) VALUES (:viewer, :id) ON CONFLICT DO NOTHING";

    static final String UNFOLLOW = "DELETE FROM follows WHERE follower_id = :viewer AND followee_id = :id";

    // ---- article lists ----

    static final String LIST_ALL = ARTICLE_LIST_SELECT + ORDER_PAGE;
    static final String COUNT_ALL = "SELECT count(*) FROM (SELECT 1 FROM articles a LIMIT 1000) s";

    static final String LIST_TAG = ARTICLE_LIST_SELECT
            + "WHERE a.id IN (SELECT at.article_id FROM article_tags at WHERE at.tag_id = (SELECT t.id FROM tags t WHERE t.name = :filter) ORDER BY at.article_id DESC LIMIT :limit OFFSET :offset)"
            + " ORDER BY a.id DESC";
    static final String COUNT_TAG =
            "SELECT count(*) FROM (SELECT 1 FROM article_tags at WHERE at.tag_id = (SELECT t.id FROM tags t WHERE t.name = :filter) LIMIT 1000) s";

    static final String LIST_AUTHOR = ARTICLE_LIST_SELECT + "WHERE a.author_id = (SELECT au.id FROM users au WHERE au.username = :filter)" + ORDER_PAGE;
    static final String COUNT_AUTHOR =
            "SELECT count(*) FROM (SELECT 1 FROM articles a WHERE a.author_id = (SELECT u.id FROM users u WHERE u.username = :filter) LIMIT 1000) s";

    static final String LIST_FAVORITED = ARTICLE_LIST_SELECT
            + "WHERE a.id IN (SELECT f.article_id FROM favorites f WHERE f.user_id = (SELECT fu.id FROM users fu WHERE fu.username = :filter) ORDER BY f.article_id DESC LIMIT :limit OFFSET :offset)"
            + " ORDER BY a.id DESC";
    static final String COUNT_FAVORITED =
            "SELECT count(*) FROM (SELECT 1 FROM favorites f WHERE f.user_id = (SELECT fu.id FROM users fu WHERE fu.username = :filter) LIMIT 1000) s";

    static final String LIST_FEED = ARTICLE_LIST_SELECT
            + "WHERE a.id IN (SELECT x.id FROM follows fo CROSS JOIN LATERAL (SELECT a2.id FROM articles a2 WHERE a2.author_id = fo.followee_id ORDER BY a2.id DESC LIMIT (:limit::int + :offset::int)) x WHERE fo.follower_id = :viewer ORDER BY x.id DESC LIMIT :limit OFFSET :offset)"
            + " ORDER BY a.id DESC";
    static final String COUNT_FEED =
            "SELECT count(*) FROM (SELECT 1 FROM articles a WHERE a.author_id IN (SELECT fo.followee_id FROM follows fo WHERE fo.follower_id = :viewer) LIMIT 1000) s";

    // ---- single articles ----

    static final String ARTICLE_BY_SLUG = ARTICLE_FULL_SELECT + "WHERE a.slug = :slug";
    static final String ARTICLE_BY_ID = ARTICLE_FULL_SELECT + "WHERE a.id = :id";

    static final String ARTICLE_INSERT = """
            INSERT INTO articles (id, slug, author_id, title, description, body)
            SELECT n.id, trim(both '-' from lower(regexp_replace(:title, '[^A-Za-z0-9]+', '-', 'g'))) || '-' || n.id, :viewer, :title, :description, :body
              FROM (SELECT nextval(pg_get_serial_sequence('articles', 'id')) AS id) n
            RETURNING id""";

    static final String TAGS_INSERT =
            "INSERT INTO tags (name) SELECT DISTINCT unnest(:tags::text[]) ON CONFLICT (name) DO NOTHING";

    static final String ARTICLE_TAGS_INSERT =
            "INSERT INTO article_tags (article_id, tag_id) SELECT :id, t.id FROM tags t WHERE t.name = ANY(:tags::text[])";

    static final String ARTICLE_OWNER = "SELECT id, author_id FROM articles WHERE slug = :slug";

    static final String ARTICLE_UPDATE = """
            UPDATE articles SET title = COALESCE(:title, title), description = COALESCE(:description, description), body = COALESCE(:body, body),
                   slug = CASE WHEN :title::text IS NULL THEN slug ELSE trim(both '-' from lower(regexp_replace(:title, '[^A-Za-z0-9]+', '-', 'g'))) || '-' || id END,
                   updated_at = now()
             WHERE id = :id""";

    static final String ARTICLE_DELETE = "DELETE FROM articles WHERE id = :id";

    static final String ARTICLE_ID_BY_SLUG = "SELECT id FROM articles WHERE slug = :slug";

    static final String FAVORITE =
            "INSERT INTO favorites (user_id, article_id) VALUES (:viewer, :id) ON CONFLICT DO NOTHING";

    static final String UNFAVORITE = "DELETE FROM favorites WHERE user_id = :viewer AND article_id = :id";

    // ---- comments ----

    static final String COMMENTS = """
            SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
                   EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = c.author_id) AS following
              FROM comments c JOIN users u ON u.id = c.author_id
             WHERE c.article_id = :id
             ORDER BY c.created_at DESC, c.id DESC LIMIT 100""";

    static final String COMMENT_INSERT = """
            WITH c AS (INSERT INTO comments (article_id, author_id, body) VALUES (:id, :viewer, :body)
                       RETURNING id, body, created_at, updated_at, author_id)
            SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image
              FROM c JOIN users u ON u.id = c.author_id""";

    static final String COMMENT_OWNER = """
            SELECT c.author_id FROM comments c JOIN articles a ON a.id = c.article_id
             WHERE c.id = :id AND a.slug = :slug""";

    static final String COMMENT_DELETE = "DELETE FROM comments WHERE id = :id";

    // ---- tags ----

    static final String TAGS = "SELECT name FROM tags ORDER BY name";

    // ---- parameters ----

    /** A parameter source whose add methods bind with explicit SQL types. */
    static final class Params extends MapSqlParameterSource {

        Params bigint(String name, long value) {
            addValue(name, value, Types.BIGINT);
            return this;
        }

        Params text(String name, String value) {
            addValue(name, value, Types.VARCHAR);
            return this;
        }

        Params textArray(String name, List<String> values) {
            addValue(name, new TextArray(values.toArray(String[]::new)));
            return this;
        }
    }

    static Params params() {
        return new Params();
    }

    /** A text[] built on the statement's own connection. */
    private record TextArray(String[] values) implements SqlValue {
        @Override
        public void setValue(PreparedStatement ps, int paramIndex) throws SQLException {
            Connection con = ps.getConnection();
            ps.setArray(paramIndex, con.createArrayOf("text", values));
        }

        @Override
        public void cleanup() {
        }
    }

    // ---- row mappers ----

    static OffsetDateTime time(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class);
    }

    static List<String> textArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        return Arrays.asList((String[]) arr.getArray());
    }

    static Dto.Article article(ResultSet rs, boolean withBody) throws SQLException {
        return new Dto.Article(
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                withBody ? rs.getString("body") : null,
                textArray(rs, "tag_list"),
                Dto.ts(time(rs, "created_at")),
                Dto.ts(time(rs, "updated_at")),
                rs.getBoolean("favorited"),
                rs.getLong("favorites_count"),
                new Dto.Profile(rs.getString("username"), rs.getString("bio"), rs.getString("image"),
                        rs.getBoolean("following")));
    }

    static final RowMapper<Dto.Article> ARTICLE_LIST_ROW = (rs, n) -> article(rs, false);
    static final RowMapper<Dto.Article> ARTICLE_FULL_ROW = (rs, n) -> article(rs, true);

    static final RowMapper<Dto.Comment> COMMENT_ROW = (rs, n) -> new Dto.Comment(
            rs.getLong("id"),
            Dto.ts(time(rs, "created_at")),
            Dto.ts(time(rs, "updated_at")),
            rs.getString("body"),
            new Dto.Profile(rs.getString("username"), rs.getString("bio"), rs.getString("image"),
                    rs.getBoolean("following")));

    static final RowMapper<Dto.Comment> NEW_COMMENT_ROW = (rs, n) -> new Dto.Comment(
            rs.getLong("id"),
            Dto.ts(time(rs, "created_at")),
            Dto.ts(time(rs, "updated_at")),
            rs.getString("body"),
            new Dto.Profile(rs.getString("username"), rs.getString("bio"), rs.getString("image"), false));

}
