package pl.coupons;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/coupons")
class CouponApi {
    private final Coupons coupons;
    private final GeoLookup geo;

    CouponApi(Coupons coupons, GeoLookup geo) {
        this.coupons = coupons;
        this.geo = geo;
    }

    record Create(String code, String country, Integer maxUses) {}
    record Redeem(String userId) {}

    @PostMapping
    @ResponseStatus(CREATED)
    Coupons.Coupon create(@RequestBody Create input) {
        return coupons.create(input.code(), input.country(), input.maxUses());
    }

    @PostMapping("/{code}/redemptions")
    @ResponseStatus(NO_CONTENT)
    void redeem(@PathVariable String code, @RequestBody Redeem input, HttpServletRequest request) {
        String key = Coupons.canonical(code);
        String user = input.userId();
        if (user == null || user.isBlank() || user.length() > 128 || user.indexOf(0) >= 0) {
            throw new Failure(BAD_REQUEST, "INVALID_USER");
        }
        coupons.redeem(key, user, geo.country(request.getRemoteAddr()));
    }
}
