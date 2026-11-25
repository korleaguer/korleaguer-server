package com.korleaguer;

import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.MinorSeasonLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Autowired
    private LeagueAverageRepository leagueAverages;

    @Autowired
    private MinorGameLogRepository minorGameLogs;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("MySQL에서 앱이 떠서 홈·선수 페이지·내 선수 데이터를 준다")
    void appRunsOnMySql() throws Exception {
        // when // then
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
        // 마이너 경기 일정(V23 칸): 시작 시각이 UTC로 왕복해 H2와 같은 한국 시각이 나온다
        mockMvc.perform(get("/api/players/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minorGames", hasSize(12)))
                .andExpect(jsonPath("$.minorGames[0].startTimeKst").value("2026-09-21T08:05:00+09:00"))
                .andExpect(jsonPath("$.minorGames[0].teamScore").value(6));
        mockMvc.perform(get("/api/my").param("ids", "1,2,3,4,5,6").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(6)));
    }

    @Test
    @DisplayName("MySQL에 넣은 리그 평균은 그대로 돌아오고 시즌·단계마다 한 줄뿐이다")
    void leagueAverageRoundTripsOnMySql() {
        // given
        // 리그 평균(V28, 이슈 #233): 반올림하지 않은 값이 그대로 돌아와야 화면 숫자가 받은 자리에서 계산한 값과 같다
        LeagueAverage.Values values = new LeagueAverage.Values(1968.38 / 6125, 711 / 6200.0, 641 * 27.0 / 4320);
        Instant fetchedAt = Instant.parse("2026-11-01T11:00:00.123456Z");
        LeagueAverage row = new LeagueAverage(2025, "A+");
        row.update(values, fetchedAt);

        // when
        leagueAverages.saveAndFlush(row);

        // then
        LeagueAverage read = leagueAverages.findBySeasonAndLevel(2025, "A+").orElseThrow();
        assertThat(read.values()).isEqualTo(values);
        assertThat(read.getFetchedAt()).isEqualTo(fetchedAt);

        // given
        // (시즌, 단계)마다 한 줄만 들어간다
        LeagueAverage again = new LeagueAverage(2025, "A+");
        again.update(values, fetchedAt);

        // when // then
        assertThatThrownBy(() -> leagueAverages.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
        leagueAverages.delete(read);

        // when // then
        // 받을 조합을 뽑는 조회(공식 날짜의 연도)도 MySQL에서 돈다. 샘플의 마이너 경기는 2026 트리플A뿐이다
        assertThat(minorGameLogs.findSeasonLevels()).containsExactly(new MinorSeasonLevel(2026, 2026, "AAA"));
    }
}
