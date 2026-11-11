package com.korleaguer.config;

import com.korleaguer.news.HttpGetter;
import com.korleaguer.news.JdkHttpGetter;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.news.PoliteFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** 관련 기사 수집기의 HTTP 연결(연결 5초·읽기 10초, 본문 2MB까지)과 예의 지키기(PoliteFetcher) */
@Configuration
public class NewsConfig {

    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    @Bean
    public HttpGetter newsHttpGetter(NewsProperties properties) {
        return new JdkHttpGetter(properties.userAgent(), Duration.ofSeconds(5), Duration.ofSeconds(10),
                MAX_FEED_BYTES);
    }

    @Bean
    public PoliteFetcher politeFetcher(HttpGetter newsHttpGetter, NewsProperties properties, Clock clock) {
        return new PoliteFetcher(newsHttpGetter, properties.robotsAgents(), clock, duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting between requests", e);
            }
        });
    }
}
