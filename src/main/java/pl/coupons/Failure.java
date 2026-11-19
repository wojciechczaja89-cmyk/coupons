package pl.coupons;

import org.springframework.http.HttpStatus;

class Failure extends RuntimeException {
    final HttpStatus status;

    Failure(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }
}
