package com.korleaguer.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** korleaguer.news.enabled=true일 때 korleaguer.news.cron(UTC)마다 관련 기사를 모은다. 지켜볼 선수가 없으면 요청하지 않는다. */
@Component
@ConditionalOnProperty(name = "korleaguer.news.enabled", havingValue = "true")
public class NewsScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduler.class);

    private final NewsCollector collector;

    public NewsScheduler(NewsCollector collector) {
        this.collector = collector;
    }

    @Scheduled(cron = "${korleaguer.news.cron}", zone = "UTC")
    public void tick() {
        try {
            collector.collect();
        } catch (RuntimeException e) {
            log.warn("news: collect failed", e);
        }
    }
}
