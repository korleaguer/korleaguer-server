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

/** 시즌 중이면 내 선수 기간은 선수마다 오늘(한국 날짜)까지다(isFallback=false). */
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
                .andExpect(jsonPath("$.players[0].isFallback").value(false))
                .andExpect(jsonPath("$.players[0].fromKst").value("2026-09-14"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(1003, 1002, 1001)))
                .andExpect(jsonPath("$.players[0].hitting.games").value(3));
    }

    @Test
    void absenceLineHasNoNumbers() throws Exception {
        // 김혜성: 2001 3타수 무안타, 2002 4타수 3안타, 2003 결장
        // 시즌 중에는 구단이 경기하는데 본인이 빠져도 기간을 당기지 않고 결장 줄을 그대로 보인다.
        // → 기간 2경기 7타수 3안타(.429). 결장은 연속 기록을 끊지 않고, 2001 무안타에서 멈춰 연속 1경기
        mockMvc.perform(get("/api/my").param("ids", "2"))
                .andExpect(jsonPath("$.players[0].isFallback").value(false))
                .andExpect(jsonPath("$.players[0].toKst").value("2026-09-27"))
                .andExpect(jsonPath("$.players[0].games[*].gameId").value(contains(2003, 2002, 2001)))
                .andExpect(jsonPath("$.players[0].games[0].played").value(false))
                .andExpect(jsonPath("$.players[0].games[0].summary").value("결장"))
                .andExpect(jsonPath("$.players[0].games[0].batting").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[0].pitching").doesNotExist())
                .andExpect(jsonPath("$.players[0].games[1].batting.hits").value(3))
                .andExpect(jsonPath("$.players[0].hitting.games").value(2))
                .andExpect(jsonPath("$.players[0].hitting.avg").value(0.429))
                .andExpect(jsonPath("$.players[0].streak").value(1));
    }
}
