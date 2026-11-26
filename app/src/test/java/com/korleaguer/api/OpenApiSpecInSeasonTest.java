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

import static org.assertj.core.api.Assertions.assertThat;

/** 시즌 중 응답도 명세(openapi.json)대로인지 본다. 홈이 최근 경기와 성적표를 채운다. */
@SpringBootTest(properties = {"springdoc.paths-to-match=/api/**", "springdoc.use-fqn=true",
        "springdoc.default-produces-media-type=application/json"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecInSeasonTest {

    @Autowired
    private MockMvc mockMvc;

    // 2026-09-27 22:00 KST: 마지막 경기일 당일이라 최근 경기가 오늘 경기다
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T13:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("시즌 중 샘플 데이터의 화면 API 응답이 모두 명세대로다")
    void inSeasonResponsesMatchSpec() throws Exception {
        // given
        String spec = OpenApiSpec.fromApp(mockMvc);

        // when
        var errors = OpenApiSpec.crawl(mockMvc, spec);

        // then
        assertThat(errors).isEmpty();
    }
}
