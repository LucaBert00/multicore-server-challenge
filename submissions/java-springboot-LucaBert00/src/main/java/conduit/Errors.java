package conduit;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error, including Spring MVC's own (404, 405, ...) and 500s, uses the SPEC shape {"errors":{"body":[msg]}}. */
final class Errors {

    private Errors() {
    }

    @RestControllerAdvice
    static class Advice {

        private static final Logger log = LoggerFactory.getLogger(Advice.class);

        @ExceptionHandler(ApiException.class)
        ResponseEntity<Dto.Errors> api(ApiException ex) {
            return ResponseEntity.status(ex.status).body(Dto.Errors.of(ex.getMessage()));
        }

        @ExceptionHandler(CannotGetJdbcConnectionException.class)
        ResponseEntity<Dto.Errors> poolTimeout(CannotGetJdbcConnectionException ex) {
            // Overload (no pooled connection within 5 s): not logged, it would flood the log.
            return ResponseEntity.internalServerError().body(Dto.Errors.of("database unavailable"));
        }

        /** Like Go's catch-all route: an unknown path, or a known path with another method, is a 404 (no 405). */
        @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
                HttpRequestMethodNotSupportedException.class})
        ResponseEntity<Dto.Errors> notFound(Exception ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Dto.Errors.of("not found"));
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<Dto.Errors> other(Exception ex) {
            if (ex instanceof ErrorResponse mvc) {
                // Other Spring MVC errors: keep the status and headers, use our body.
                HttpStatusCode status = mvc.getStatusCode();
                HttpStatus known = HttpStatus.resolve(status.value());
                String message = known != null ? known.getReasonPhrase().toLowerCase() : "error";
                return ResponseEntity.status(status).headers(mvc.getHeaders()).body(Dto.Errors.of(message));
            }
            log.error("unhandled", ex);
            return ResponseEntity.internalServerError().body(Dto.Errors.of("internal server error"));
        }
    }

    /** Replaces Spring Boot's /error JSON for errors raised outside a handler (e.g. by Tomcat). */
    @RestController
    static class Fallback implements ErrorController {

        @RequestMapping("/error")
        ResponseEntity<Dto.Errors> error(HttpServletRequest request) {
            Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            int status = code instanceof Integer i ? i : 404;
            HttpStatus known = HttpStatus.resolve(status);
            String message = known != null ? known.getReasonPhrase().toLowerCase() : "error";
            return ResponseEntity.status(status).body(Dto.Errors.of(message));
        }

        /** Spring answers OPTIONS on known paths itself (200 + Allow); the API has no OPTIONS routes: 404. */
        @RequestMapping(path = "/**", method = RequestMethod.OPTIONS)
        ResponseEntity<Dto.Errors> options() {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Dto.Errors.of("not found"));
        }
    }
}
