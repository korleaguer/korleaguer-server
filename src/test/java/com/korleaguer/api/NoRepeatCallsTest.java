package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면용 API는 DB만 읽는다. 같은 화면을 몇 번 열어도 MLB(가짜 클라이언트)의 요청 수가 늘지 않는다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:no-repeat-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoRepeatCallsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MlbClient client;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void screensReadTheDbOnly() throws Exception {
        long before = client.requestCount();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/home")).andExpect(status().isOk());
            mockMvc.perform(get("/api/players")).andExpect(status().isOk());
            mockMvc.perform(get("/api/players/1")).andExpect(status().isOk());
            mockMvc.perform(get("/api/players/5")).andExpect(status().isOk());
            mockMvc.perform(get("/api/my").param("ids", "1,5")).andExpect(status().isOk());
            mockMvc.perform(get("/api/my").param("ids", "2").param("days", "30")).andExpect(status().isOk());
        }

        assertThat(client.requestCount()).isEqualTo(before);
    }
}
