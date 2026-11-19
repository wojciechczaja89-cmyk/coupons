package pl.coupons;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CountryIsTest {
    @ParameterizedTest
    @ValueSource(strings = {"PL", "ZZ", "malformed", "outage"})
    void readsCountryOrReportsUnavailable(String scenario) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/8.8.8.8", exchange -> {
            byte[] body = (scenario.equals("malformed") ? "?" : "{\"country\":\"" + scenario + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(scenario.equals("outage") ? 503 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var lookup = new CountryIs("http://127.0.0.1:" + server.getAddress().getPort());
            if (scenario.equals("PL")) {
                assertThat(lookup.country("8.8.8.8")).isEqualTo("PL");
            } else {
                assertThatThrownBy(() -> lookup.country("8.8.8.8"))
                        .isInstanceOf(Failure.class).hasMessage("COUNTRY_UNAVAILABLE");
            }
        } finally {
            server.stop(0);
        }
    }
}
