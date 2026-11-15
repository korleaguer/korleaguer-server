package com.korleaguer.config;

import com.korleaguer.api.SavantClips;
import com.korleaguer.news.JdkHttpGetter;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.news.PoliteFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * 경기 상세의 공 하나 영상(Baseball Savant). 뉴스 수집과 따로 PoliteFetcher를 둔다:
 * PoliteFetcher는 한 번에 하나씩만 받아서, 뉴스를 받는 동안 사용자 요청이 기다리지 않게 한다.
 * 영상 페이지는 크지 않아(90KB 안팎) 본문을 512KB까지만 받는다. 연결 3초·읽기 5초(사람이 기다리는 요청이라 짧게).
 */
@Configuration
public class VideoConfig {

    private static final int MAX_PAGE_BYTES = 512 * 1024;

    @Bean
    public SavantClips savantClips(KorleaguerProperties properties, NewsProperties news, Clock clock) {
        JdkHttpGetter http = new JdkHttpGetter(properties.mlb().userAgent(), Duration.ofSeconds(3),
                Duration.ofSeconds(5), MAX_PAGE_BYTES);
        return new SavantClips(new PoliteFetcher(http, news.robotsAgents(), clock, VideoConfig::sleep));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
