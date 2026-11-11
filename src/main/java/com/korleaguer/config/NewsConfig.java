package com.korleaguer.config;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.korleaguer.news.ClaudeSummarizer;
import com.korleaguer.news.HttpGetter;
import com.korleaguer.news.JdkHttpGetter;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.news.Summarizer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;

/** 기사 피드·사진을 받는 HTTP 연결(연결 5초·읽기 10초, 본문 2MB까지)과 예의 지키기(PoliteFetcher, 기사용·사진용 따로) */
@Configuration
public class NewsConfig {

    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    @Bean
    public HttpGetter newsHttpGetter(NewsProperties properties) {
        return new JdkHttpGetter(properties.userAgent(), Duration.ofSeconds(5), Duration.ofSeconds(10),
                MAX_FEED_BYTES);
    }

    /**
     * 기사 요약기. 서버 환경 변수 ANTHROPIC_API_KEY(Claude Console에서 만든 키, /srv/korleaguer/api.env)가 있을 때만 켠다.
     * 없으면 요약도 기사 페이지 받기도 하지 않는다.
     */
    @Bean
    public Summarizer summarizer(@Value("${ANTHROPIC_API_KEY:}") String apiKey,
                                 @Value("${korleaguer.news.summary-model:claude-haiku-5-5}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            LoggerFactory.getLogger(NewsConfig.class).info("news: ANTHROPIC_API_KEY is not set, article summaries are off");
            return Summarizer.OFF;
        }
        return new ClaudeSummarizer(AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build(), model);
    }

    @Bean
    @Primary
    public PoliteFetcher politeFetcher(HttpGetter newsHttpGetter, NewsProperties properties, Clock clock) {
        return new PoliteFetcher(newsHttpGetter, properties.robotsAgents(), clock, NewsConfig::sleep);
    }

    /**
     * 사진(PhotoStore)용. 사람이 고른 사진 주소만 받으므로, robots.txt가 401·403이면(그림 말고는 다 막는 사진 서버)
     * 표준(RFC 9309)대로 'robots.txt 없음'으로 본다. 사진 자체가 403이면 그 사이트를 하루 쉬는 것은 같다.
     */
    @Bean
    public PoliteFetcher photoFetcher(HttpGetter newsHttpGetter, NewsProperties properties, Clock clock) {
        return new PoliteFetcher(newsHttpGetter, properties.robotsAgents(), clock, NewsConfig::sleep, false);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting between requests", e);
        }
    }
}
