package com.korleaguer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영과 같은 MySQL 8.4에서 앱이 뜨는지 확인한다: Flyway 마이그레이션, Hibernate 스키마 검증(validate),
 * 시작할 때 샘플 수집(쓰기), 화면용 API(읽기)와 UTC 시각 왕복. 다른 테스트는 H2(MySQL 모드)로 돈다.
 * Docker가 없으면 건너뛴다(CI에는 있다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MySqlCompatibilityTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

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
    void appRunsOnMySql() throws Exception {
        // 시각(datetime(6), UTC)이 그대로 돌아와 한국 날짜·시각이 H2와 같게 나온다(ApiIntegrationTest와 같은 값)
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(6)))
                .andExpect(jsonPath("$.recent.lines[0].nameKo").value("이정후"))
                .andExpect(jsonPath("$.recent.lines[0].startTimeKst").value("2026-09-27T05:05:00+09:00"))
                .andExpect(jsonPath("$.recent.lines[?(@.nameKo == '고우석')].lineup").value(contains("SUB")))
                .andExpect(jsonPath("$.ranking.teamGames").value(3))
                .andExpect(jsonPath("$.hitters", hasSize(5)))
                .andExpect(jsonPath("$.hitters[*].nameKo").value(contains("이정후", "송성문", "김하성", "김혜성", "배지환")))
                .andExpect(jsonPath("$.hitters[4].minorGames").value(20))
                .andExpect(jsonPath("$.pitchers[0].era").value(10.8));
        mockMvc.perform(get("/api/players/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player.nameKo").value("이정후"))
                .andExpect(jsonPath("$.moves").isArray());
        mockMvc.perform(get("/api/my").param("ids", "1,2,3,4,5,6").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(6)));
    }
}
