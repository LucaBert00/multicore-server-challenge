package conduit;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code Authorization: Token <jwt>} (an empty header counts as absent). A handler parameter of type {@link User} makes auth required
 * (missing or invalid token: 401); {@link Viewer} makes it optional (no header: viewer 0; a header
 * that is present but invalid: 401). Declared first in each handler, so auth runs before anything else.
 */
@Configuration(proxyBeanMethods = false)
class Auth implements WebMvcConfigurer, HandlerMethodArgumentResolver {

    /** Authenticated caller: user id and the token they presented. */
    record User(long id, String token) {
    }

    /** Optional caller: user id, or 0 when anonymous. */
    record Viewer(long id) {
    }

    private static final String PREFIX = "Token ";

    private final Jwt jwt;

    Auth(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == User.class || type == Viewer.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isEmpty()) {
            if (parameter.getParameterType() == Viewer.class) {
                return new Viewer(0);
            }
            throw ApiException.unauthorized();
        }
        if (!header.startsWith(PREFIX)) {
            throw ApiException.unauthorized();
        }
        String token = header.substring(PREFIX.length());
        long id = jwt.verify(token);
        if (id == 0) {
            throw ApiException.unauthorized();
        }
        return parameter.getParameterType() == User.class ? new User(id, token) : new Viewer(id);
    }
}
