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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 지금이 마지막 경기 직후라면 최근 24시간 경기를 그대로 보여 준다(isFallback=false). */
@SpringBootTest
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
    void showsLast24Hours() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.recent.isFallback").value(false))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(6)));
    }
}
