package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.domain.PlayerSeasonRepository;
import org.junit.jupiter.api.DisplayName;
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
 * 선수 시즌을 아직 받지 않았어도 선수 페이지는 그 자리에서 받지 않는다(이슈 #231. 스케줄 수집이 받는다).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:no-repeat-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoRepeatCallsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MlbClient client;

    @Autowired
    private PlayerSeasonRepository seasons;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("홈·선수 목록·선수·내 선수 화면을 다시 열어도 MLB 요청이 늘지 않는다")
    void screensReadTheDbOnly() throws Exception {
        // given
        long before = client.requestCount();

        // when // then
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

    @Test
    @DisplayName("시즌 기록을 아직 못 받은 선수의 화면을 열어도 MLB에 새로 요청하지 않는다")
    void playerPageDoesNotFetchAMissingSeason() throws Exception {
        // given
        // 김혜성(id 2)의 이번 시즌 "받았음" 표시를 지워 아직 받지 않은 상태로 만든다.
        seasons.delete(seasons.find(2L, 2026).orElseThrow());
        long before = client.requestCount();

        // when // then
        // DB에 있는 것만 보여 주고 MLB를 부르지 않는다. "받았음" 표시도 그대로 없다(다음 스케줄 수집이 받는다)
        mockMvc.perform(get("/api/players/2")).andExpect(status().isOk());

        assertThat(client.requestCount()).isEqualTo(before);
        assertThat(seasons.exists(2L, 2026)).isFalse();
    }
}
