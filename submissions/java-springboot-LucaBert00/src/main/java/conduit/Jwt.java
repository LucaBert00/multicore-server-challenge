package conduit;

import java.time.Duration;
import java.time.Instant;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HS256 tokens: {"sub": "<user id>", "iat": now, "exp": now + 30 days}. Verified on every request, no cache. */
@Component
class Jwt {

    private static final Duration TTL = Duration.ofDays(30);

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    Jwt(@Value("${JWT_SECRET}") String secret) {
        this.algorithm = Algorithm.HMAC256(secret);
        // Checks the signature, that alg is HS256, that exp is present and not past (plus nbf if present).
        this.verifier = JWT.require(algorithm).withClaimPresence("exp").ignoreIssuedAt().build();
    }

    String sign(long userId) {
        Instant now = Instant.now();
        return JWT.create()
                .withSubject(Long.toString(userId))
                .withIssuedAt(now)
                .withExpiresAt(now.plus(TTL))
                .sign(algorithm);
    }

    /** The token's user id, or 0 if the token is invalid or expired. */
    long verify(String token) {
        try {
            // sub must be a JSON string of digits (asString() is null for a number), as golang-jwt requires.
            long id = Body.parseUint(verifier.verify(token).getClaim("sub").asString());
            return id > 0 ? id : 0;
        } catch (JWTVerificationException ex) {
            return 0;
        }
    }
}
