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

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 모양 명세(저장소 맨 위 openapi.json)가 코드와 같은지, 실제 응답이 명세대로인지 본다(비시즌).
 * 응답 record를 바꿨으면 명세를 다시 만든다: OPENAPI_UPDATE=1 ./gradlew :app:test --tests com.korleaguer.api.OpenApiSpecTest
 */
@SpringBootTest(properties = {"springdoc.paths-to-match=/api/**", "springdoc.use-fqn=true",
        "springdoc.default-produces-media-type=application/json"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecTest {

    @Autowired
    private MockMvc mockMvc;

    // 2026-11-01: 비시즌. 홈이 비시즌 칸(포스팅·이동)을 채운다
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("저장소의 응답 모양 명세가 지금 코드에서 뽑은 명세와 같다")
    void specFileIsUpToDate() throws Exception {
        // given
        String fromCode = OpenApiSpec.fromApp(mockMvc);
        if (System.getenv("OPENAPI_UPDATE") != null) {
            Files.writeString(OpenApiSpec.FILE, fromCode);
        }

        // when
        String saved = Files.exists(OpenApiSpec.FILE) ? Files.readString(OpenApiSpec.FILE) : "";

        // then
        assertThat(saved)
                .as("openapi.json이 코드와 다르다. 다시 만들기: "
                        + "OPENAPI_UPDATE=1 ./gradlew :app:test --tests com.korleaguer.api.OpenApiSpecTest")
                .isEqualTo(fromCode);
    }

    @Test
    @DisplayName("비시즌 샘플 데이터의 화면 API 응답이 모두 명세대로다")
    void offSeasonResponsesMatchSpec() throws Exception {
        // given
        String spec = OpenApiSpec.fromApp(mockMvc);

        // when
        var errors = OpenApiSpec.crawl(mockMvc, spec);

        // then
        assertThat(errors).isEmpty();
    }
}
