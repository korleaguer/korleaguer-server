package com.korleaguer.config;

import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.posting.PostingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 캐시는 지금 메모리(Caffeine, 15분 만료)에 둔다. 설정은 application.properties의 spring.cache.*.
 * 데이터는 수집 때만 바뀌고 그때 캐시를 비운다(IngestService). 따라잡기가 끝나면 CacheWarmer가 다시 채운다.
 * 서버를 여러 대로 늘리면 Redis로 바꿀 자리다(spring-boot-starter-data-redis + spring.cache.type=redis).
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties({KorleaguerProperties.class, RosterProperties.class, PostingProperties.class,
        NewsProperties.class})
public class AppConfig {

    public static final String HOME_CACHE = "home";
    public static final String PLAYER_CACHE = "player";
    /** 내 선수(GET /api/my). 선수 조합(ids)과 기간(days)마다 따로 둔다 */
    public static final String MY_CACHE = "my";

    // 테스트에서 시각을 고정할 수 있게 Clock을 빈으로 둔다.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 뒤에서 도는 수집(앱 시작 때 따라잡기, 선수 페이지에서 시즌 받기). 앱이 내려갈 때 멈춘다. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService ingestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
