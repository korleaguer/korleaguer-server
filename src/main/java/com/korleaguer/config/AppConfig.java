package com.korleaguer.config;

import com.korleaguer.ingest.RosterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 캐시는 지금 메모리(Caffeine, 60초 만료)에 둔다. 설정은 application.properties의 spring.cache.*.
 * 서버를 여러 대로 늘리면 Redis로 바꿀 자리다(spring-boot-starter-data-redis + spring.cache.type=redis).
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties({KorleaguerProperties.class, RosterProperties.class})
public class AppConfig {

    public static final String HOME_CACHE = "home";
    public static final String PLAYER_CACHE = "player";

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
