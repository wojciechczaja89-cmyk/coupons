package pl.coupons;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
class CountryIs implements GeoLookup {
    private final RestClient client;

    CountryIs(@Value("${geo.base-url}") String baseUrl) {
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(Duration.ofSeconds(2));
        transport.setReadTimeout(Duration.ofSeconds(2));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(transport).build();
    }

    record Result(String country) {}

    public String country(String ip) {
        try {
            Result result = client.get().uri("/{ip}", ip).retrieve().body(Result.class);
            if (result != null && Countries.valid(result.country())) {
                return result.country();
            }
        } catch (RestClientException ignored) {
            // Transport, provider errors and invalid bodies share the same API response.
        }
        throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "COUNTRY_UNAVAILABLE");
    }
}
