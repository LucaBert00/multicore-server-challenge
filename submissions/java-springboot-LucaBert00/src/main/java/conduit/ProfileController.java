package conduit;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profiles and follows. */
@RestController
class ProfileController {

    private record Target(long id, String username, String bio, String image) {
    }

    private final NamedParameterJdbcTemplate db;

    ProfileController(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/api/profiles/{username}")
    Map<String, Dto.Profile> get(Auth.Viewer viewer, @PathVariable String username) {
        List<Dto.Profile> rows = db.query(Sql.PROFILE,
                Sql.params().bigint("viewer", viewer.id()).text("username", username),
                (rs, n) -> new Dto.Profile(rs.getString("username"), rs.getString("bio"), rs.getString("image"),
                        rs.getBoolean("following")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("profile");
        }
        return Map.of("profile", rows.get(0));
    }

    @PostMapping("/api/profiles/{username}/follow")
    Map<String, Dto.Profile> follow(Auth.User auth, @PathVariable String username) {
        return setFollow(auth, username, true);
    }

    @DeleteMapping("/api/profiles/{username}/follow")
    Map<String, Dto.Profile> unfollow(Auth.User auth, @PathVariable String username) {
        return setFollow(auth, username, false);
    }

    private Map<String, Dto.Profile> setFollow(Auth.User auth, String username, boolean follow) {
        List<Target> rows = db.query(Sql.USER_BY_USERNAME, Sql.params().text("username", username),
                (rs, n) -> new Target(rs.getLong("id"), rs.getString("username"), rs.getString("bio"),
                        rs.getString("image")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("profile");
        }
        Target t = rows.get(0);
        db.update(follow ? Sql.FOLLOW : Sql.UNFOLLOW, Sql.params().bigint("viewer", auth.id()).bigint("id", t.id()));
        return Map.of("profile", new Dto.Profile(t.username(), t.bio(), t.image(), follow));
    }
}
