package conduit;

import org.springframework.http.HttpStatus;

/** An error rendered as {"errors": {"body": ["<message>"]}} with the given status. No stack trace. */
class ApiException extends RuntimeException {

    final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden");
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
