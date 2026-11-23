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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/records(한국인 메이저리거 역대 기록, 이슈 #177). 샘플 모드: 명단 6명의 시즌별 기록(sample/mlb.json yearByYear)만 있고
 * 명단 밖 선수(korean-mlb.yml)는 샘플에 기록이 없어 순위에 나오지 않는다. 지금을 2026-11-01로 고정한다.
 * 샘플 이정후 안타 38 + 149 + 149 = 336, 김하성 130 + 140 + 94 + 15 = 379(샘플에 2021·2025 줄이 없다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void ranksTheRosterPlayersFromTheirSeasonLines() throws Exception {
        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("sample"))
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.categories[0].key").value("hits"))
                .andExpect(jsonPath("$.categories[0].label").value("안타"))
                .andExpect(jsonPath("$.categories[0].group").value("HITTING"))
                .andExpect(jsonPath("$.categories[0].leaders[0].nameKo").value("김하성"))
                .andExpect(jsonPath("$.categories[0].leaders[0].value").value(379))
                .andExpect(jsonPath("$.categories[0].leaders[0].firstSeason").value(2022))
                .andExpect(jsonPath("$.categories[0].leaders[1].nameKo").value("이정후"))
                .andExpect(jsonPath("$.categories[0].leaders[1].value").value(336))
                .andExpect(jsonPath("$.categories[0].leaders[1].rank").value(2))
                .andExpect(jsonPath("$.categories[0].leaders[1].active").value(true))
                .andExpect(jsonPath("$.categories[0].leaders[1].photoUrl").value(org.hamcrest.Matchers.matchesPattern("/photos/mlb-808982(\\?v=[0-9a-f]+)?")))
                // 고우석은 국내 리그로 돌아가(명단 returned-ko) 현역이 아니다. 투수라 승·탈삼진 쪽에만 나온다
                .andExpect(jsonPath("$.categories[?(@.key == 'strikeouts')].leaders[0].nameKo").value("고우석"))
                .andExpect(jsonPath("$.categories[?(@.key == 'strikeouts')].leaders[0].active").value(false))
                // 이정후 안타 336 → 김하성 379를 넘으려면 44개, 넘으면 1위
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].targetNameKo").value("김하성"))
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].toPass").value(44))
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].rankAfter").value(1))
                // 샘플에는 현역이 아닌 타자가 없어 넘은 선배가 없다
                .andExpect(jsonPath("$.passed", hasSize(0)))
                .andExpect(jsonPath("$.singleSeason[0].key").value("hits"))
                .andExpect(jsonPath("$.singleSeason[0].rows[0].nameKo").value("이정후"))
                .andExpect(jsonPath("$.singleSeason[0].rows[0].season").value(2025));
    }
}
