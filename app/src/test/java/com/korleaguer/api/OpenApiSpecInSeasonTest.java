package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 시즌 중 응답도 명세(openapi.json)대로인지 본다. 홈이 최근 경기와 성적표를 채운다. */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.paths-to-match=/api/**", "springdoc.use-fqn=true",
        "springdoc.default-produces-media-type=application/json"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// 이 설정의 컨텍스트는 이 클래스만 쓴다. 끝나면 내려놓아 테스트 전체의 메모리를 늘리지 않는다
@DirtiesContext
class OpenApiSpecInSeasonTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewsArticleRepository news;

    // 샘플 수집은 기사를 받지 않는다. 기사 목록·기사 페이지도 부르게 두 건 넣는다(하나는 번역·요약까지 된 기사)
    @BeforeEach
    void saveArticles() {
        Instant at = Instant.parse("2026-09-20T03:00:00Z");
        news.save(new NewsArticle("이정후", "https://news.example/1", "h1", "이정후 3안타", "마이데일리", at,
                at.plusSeconds(60)));
        NewsArticle enriched = new NewsArticle("김하성", "https://news.example/2", "h2", "Kim homers", "MLB.com",
                at.minusSeconds(600), at.plusSeconds(60));
        enriched.enriched("https://img.example/2.jpg", "김하성 홈런", "김하성이 홈런을 쳤다.", "test-model", at);
        news.save(enriched);
    }

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
