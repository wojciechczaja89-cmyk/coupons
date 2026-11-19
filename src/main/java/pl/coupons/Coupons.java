package pl.coupons;

import java.time.Instant;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.http.HttpStatus.*;

@Service
class Coupons {
    private final JdbcClient db;

    Coupons(JdbcClient db) {
        this.db = db;
    }

    record Coupon(String code, Instant createdAt, String country, int maxUses, int uses) {}

    static String canonical(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new Failure(BAD_REQUEST, "INVALID_CODE");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    Coupon create(String rawCode, String country, Integer maxUses) {
        String code = canonical(rawCode);
        if (!Countries.valid(country) || maxUses == null || maxUses <= 0) {
            throw new Failure(BAD_REQUEST, "INVALID_COUPON");
        }
        return db.sql("""
                INSERT INTO coupon(code, country, max_uses) VALUES (?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                RETURNING code, created_at, country, max_uses, uses
                """).params(code, country, maxUses).query(Coupon.class).optional()
                .orElseThrow(() -> new Failure(CONFLICT, "CODE_EXISTS"));
    }

    @Transactional
    public void redeem(String code, String userId, String country) {
        Coupon coupon = db.sql("SELECT * FROM coupon WHERE code = ? FOR UPDATE")
                .param(code).query(Coupon.class).optional()
                .orElseThrow(() -> new Failure(NOT_FOUND, "UNKNOWN_CODE"));
        if (!coupon.country().equals(country)) {
            throw new Failure(FORBIDDEN, "WRONG_COUNTRY");
        }
        int inserted = db.sql("""
                INSERT INTO redemption(code, user_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """).params(code, userId).update();
        if (inserted == 0) {
            throw new Failure(CONFLICT, "ALREADY_USED");
        }
        if (coupon.uses() == coupon.maxUses()) {
            throw new Failure(CONFLICT, "LIMIT_REACHED");
        }
        db.sql("UPDATE coupon SET uses = uses + 1 WHERE code = ?").param(code).update();
    }
}
