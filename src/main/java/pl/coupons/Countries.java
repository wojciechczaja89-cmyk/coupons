package pl.coupons;

import java.util.Locale;
import java.util.Set;

final class Countries {
    private static final Set<String> ISO = Set.of(Locale.getISOCountries());

    static boolean valid(String country) {
        return country != null && ISO.contains(country);
    }
}
