package pl.coupons;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CouponApiTest {
    static EmbeddedPostgres pg;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) throws Exception {
        pg = EmbeddedPostgres.builder().start();
        props.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        props.add("spring.datasource.username", () -> "postgres");
        props.add("spring.datasource.password", () -> "");
    }

    @AfterAll static void stopDatabase() throws Exception { pg.close(); }
    @Autowired MockMvc api;
    @Autowired JdbcClient db;
    @MockitoBean GeoLookup geo;

    @BeforeEach void reset() {
        db.sql("TRUNCATE coupon, redemption").update();
        when(geo.country("127.0.0.1")).thenReturn("PL");
    }

    org.springframework.test.web.servlet.ResultActions create(String code, int limit) throws Exception {
        String json = """
                {"code":"%s","country":"PL","maxUses":%d}
                """.formatted(code, limit);
        return api.perform(post("/coupons").contentType("application/json").content(json));
    }

    org.springframework.test.web.servlet.ResultActions use(String code, String user) throws Exception {
        return api.perform(post("/coupons/{code}/redemptions", code)
                .contentType("application/json").content("{\"userId\":\"" + user + "\"}"));
    }

    @Test void creationValidatesAndNormalizesCode() throws Exception {
        create("wiosna", 1).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("WIOSNA"))
                .andExpect(jsonPath("$.uses").value(0)).andExpect(jsonPath("$.createdAt").isString());
        create("WiOsNa", 2).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CODE_EXISTS"));
        create("x", 0).andExpect(status().isBadRequest());
        create("?", 1).andExpect(status().isBadRequest());
        use("x", " ").andExpect(status().isBadRequest());
    }

    @Test void failuresPreserveCapacityAndReturnDistinctReasons() throws Exception {
        use("x", "a").andExpect(status().isNotFound());
        create("X", 1).andExpect(status().isCreated());
        when(geo.country("127.0.0.1")).thenReturn("DE");
        use("x", "a").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("WRONG_COUNTRY"));
        when(geo.country("127.0.0.1")).thenReturn("PL");
        use("x", "a").andExpect(status().isNoContent());
        use("X", "a").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_USED"));
        use("x", "b").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("LIMIT_REACHED"));
        assertCounts(1);
    }

    @Test void onlyOneUserGetsLastUse() throws Exception {
        race(1, false);
    }

    @Test void simultaneousRetriesConsumeOnlyOneUse() throws Exception {
        race(10, true);
    }

    void race(int limit, boolean sameUser) throws Exception {
        create("race", limit).andExpect(status().isCreated());
        int clients = 8;
        var gate = new CyclicBarrier(clients);
        try (var pool = Executors.newFixedThreadPool(clients)) {
            List<Future<Integer>> replies = IntStream.range(0, clients).mapToObj(i ->
                    pool.submit(() -> {
                        gate.await(10, TimeUnit.SECONDS);
                        return use("race", sameUser ? "same" : "user-" + i).andReturn().getResponse().getStatus();
                    })).toList();
            int winners = 0;
            for (var reply : replies) {
                int status = reply.get(20, TimeUnit.SECONDS);
                assertThat(status).isIn(204, 409);
                if (status == 204) winners++;
            }
            assertThat(winners).isEqualTo(1);
        }
        assertCounts(1);
    }

    void assertCounts(int expected) {
        assertThat(db.sql("SELECT uses FROM coupon").query(Integer.class).single()).isEqualTo(expected);
        assertThat(db.sql("SELECT count(*) FROM redemption").query(Integer.class).single()).isEqualTo(expected);
    }
}
