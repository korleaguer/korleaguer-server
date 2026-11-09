package com.korleaguer.api;

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

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시즌 중이면 내 선수 기간은 오늘(한국 날짜)까지다(isFallback=false). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyPlayersInSeasonTest {

    @Autowired
    private MockMvc mockMvc;

    // 2026-09-27 22:00 KST. 마지막 경기(9월 27일 KST)가 오늘이라 대신하지 않는다.
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T13:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void windowEndsToday() throws Exception {
        mockMvc.perform(get("/api/my").param("ids", "1").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFallback").value(false))
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.fromKst").value("2026-09-14"))
                .andExpect(jsonPath("$.toKst").value("2026-09-27"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(1003, 1002, 1001)))
                .andExpect(jsonPath("$.players[0].hitting.games").value(3));
    }
}
