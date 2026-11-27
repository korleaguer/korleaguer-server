package com.korleaguer.api;

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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 지금이 마지막 경기 직후라면 최근 24시간 경기를 그대로 보여 준다(isFallback=false). */
// 공용 테스트 DB를 쓰지 않는다: 시각이 시즌 중이라, 이 테스트가 먼저 떠서 공용 DB를 채우면 막 끝난 경기가 굳지 않은 채 남는다
// (선발·교체가 비어 비시즌 테스트가 깨진다, 이슈 #335)
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:recent-window-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecentWindowTest {

    @Autowired
    private MockMvc mockMvc;

    // 2026-09-27 12:00 KST. 24시간 안에 각 선수의 9월 27일(KST) 경기가 하나씩 있다.
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("마지막 경기 직후 홈을 열면 최근 24시간 경기와 화제 문구가 나온다")
    void showsLast24Hours() throws Exception {
        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.recent.isFallback").value(false))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(6)))
                // 화제 문구는 줄마다 최대 하나, 없는 줄은 빠진다(필드는 늘 배열)
                .andExpect(jsonPath("$.recent.buzz").isArray())
                .andExpect(jsonPath("$.recent.buzz[*].text", hasItem("올 시즌 첫 홈런")))
                .andExpect(jsonPath("$.recent.buzz[?(@.playerId==2)]").isEmpty());
    }
}
