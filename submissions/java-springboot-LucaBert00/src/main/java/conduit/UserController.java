package conduit;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Users and auth: register, login, current user, update user. */
@RestController
class UserController {

    /** bcrypt cost 10 for every new hash; verification uses the cost stored in the hash. */
    static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

    private record Row(long id, String username, String email, String bio, String image) {
    }

    private record LoginRow(Row user, String passwordHash) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("id"), rs.getString("username"),
            rs.getString("email"), rs.getString("bio"), rs.getString("image"));

    private final NamedParameterJdbcTemplate db;
    private final Jwt jwt;
    private final Body body;

    UserController(NamedParameterJdbcTemplate db, Jwt jwt, Body body) {
        this.db = db;
        this.jwt = jwt;
        this.body = body;
    }

    private static Map<String, Dto.User> wrap(Row r, String token) {
        return Map.of("user", new Dto.User(r.email(), token, r.username(), r.bio(), r.image()));
    }

    @PostMapping("/api/users")
    ResponseEntity<Map<String, Dto.User>> register(HttpServletRequest request) {
        JsonNode user = body.object(request, "user");
        String username = Body.required(user, "username");
        String email = Body.required(user, "email");
        String password = Body.required(user, "password");
        String hash = BCRYPT.encode(Body.hashable(password));
        Row row;
        try {
            row = db.queryForObject(Sql.USER_INSERT,
                    Sql.params().text("username", username).text("email", email).text("hash", hash), ROW);
        } catch (DuplicateKeyException ex) {
            throw ApiException.invalid("username or email has already been taken");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(row, jwt.sign(row.id())));
    }

    @PostMapping("/api/users/login")
    Map<String, Dto.User> login(HttpServletRequest request) {
        JsonNode user = body.object(request, "user");
        String email = Body.required(user, "email");
        String password = Body.required(user, "password");
        List<LoginRow> rows = db.query(Sql.USER_BY_EMAIL, Sql.params().text("email", email),
                (rs, n) -> new LoginRow(ROW.mapRow(rs, n), rs.getString("password_hash")));
        if (rows.isEmpty() || !BCRYPT.matches(password, rows.get(0).passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "email or password is invalid");
        }
        Row row = rows.get(0).user();
        return wrap(row, jwt.sign(row.id()));
    }

    @GetMapping("/api/user")
    Map<String, Dto.User> current(Auth.User auth) {
        List<Row> rows = db.query(Sql.USER_BY_ID, Sql.params().bigint("id", auth.id()), ROW);
        if (rows.isEmpty()) {
            throw ApiException.unauthorized();
        }
        return wrap(rows.get(0), auth.token());
    }

    @PutMapping("/api/user")
    Map<String, Dto.User> update(Auth.User auth, HttpServletRequest request) {
        JsonNode user = body.object(request, "user");
        String email = Body.optionalNonEmpty(user, "email");
        String username = Body.optionalNonEmpty(user, "username");
        String password = Body.optionalNonEmpty(user, "password");
        String bio = Body.optional(user, "bio");
        String image = Body.optional(user, "image");
        String hash = password == null ? null : BCRYPT.encode(Body.hashable(password));
        List<Row> rows;
        try {
            rows = db.query(Sql.USER_UPDATE, Sql.params().bigint("id", auth.id()).text("email", email)
                    .text("username", username).text("hash", hash).text("bio", bio).text("image", image), ROW);
        } catch (DuplicateKeyException ex) {
            throw ApiException.invalid("username or email has already been taken");
        }
        if (rows.isEmpty()) {
            throw ApiException.unauthorized();
        }
        return wrap(rows.get(0), auth.token());
    }
}
