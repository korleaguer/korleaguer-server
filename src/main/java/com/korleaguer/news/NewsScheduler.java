package com.korleaguer.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * korleaguer.news.enabled=true일 때 korleaguer.news.cron(UTC)마다 관련 기사를 모으고, 요약을 허락한 출처의 새 기사에
 * 요약을 붙인다(NewsEnricher). 지켜볼 선수가 없으면 피드를 받지 않는다.
 */
@Component
@ConditionalOnProperty(name = "korleaguer.news.enabled", havingValue = "true")
public class NewsScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduler.class);

    private final NewsCollector collector;
    private final NewsEnricher enricher;

    public NewsScheduler(NewsCollector collector, NewsEnricher enricher) {
        this.collector = collector;
        this.enricher = enricher;
    }

    @Scheduled(cron = "${korleaguer.news.cron}", zone = "UTC")
    public void tick() {
        try {
            collector.collect();
        } catch (RuntimeException e) {
            log.warn("news: collect failed", e);
        }
        try {
            enricher.enrich();
        } catch (RuntimeException e) {
            log.warn("news: summarize failed", e);
        }
    }
}
