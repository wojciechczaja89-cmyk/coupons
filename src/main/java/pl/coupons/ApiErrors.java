package pl.coupons;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class ApiErrors {
    record ErrorBody(String error) {}

    @ExceptionHandler(Failure.class)
    ResponseEntity<ErrorBody> domain(Failure failure) {
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformed() {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_JSON"));
    }
}
