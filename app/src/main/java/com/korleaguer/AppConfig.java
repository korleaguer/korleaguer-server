package com.korleaguer;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KoreanMlbProperties;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.MinorTeamProperties;
import com.korleaguer.config.NewsProperties;
import com.korleaguer.config.RosterProperties;
import com.korleaguer.posting.PostingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 캐시는 지금 메모리(Caffeine)에 둔다. 설정은 application.properties의 spring.cache.*.
 * 보이는 것이 실제로 바뀔 때만 비우고(ResponseCaches), 비운 뒤와 따라잡기 뒤에 CacheWarmer가 다시 채운다.
 * 서버를 여러 대로 늘리면 Redis로 바꿀 자리다(spring-boot-starter-data-redis + spring.cache.type=redis).
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties({KorleaguerProperties.class, RosterProperties.class, PostingProperties.class,
        NewsProperties.class, MinorTeamProperties.class, KoreanMlbProperties.class})
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * 데이터 출처(korleaguer.data-source: 실제 MLB 또는 샘플). 외부 호출(client)의 MLB 클라이언트 고르기, 지금 시즌 정하기(core),
     * 응답의 dataMode(api)가 함께 쓰므로 모듈을 묶는 여기에 둔다
     */
    @Bean
    public DataMode dataMode(KorleaguerProperties properties) {
        DataMode mode = DataMode.resolve(properties.dataSource());
        log.info("data source: {}", mode.value());
        return mode;
    }

    // 테스트에서 시각을 고정할 수 있게 Clock을 빈으로 둔다.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 뒤에서 도는 일(앱 시작 때 따라잡기, 사진 받기, 응답 캐시 채우기). 앱이 내려갈 때 멈춘다. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService ingestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
