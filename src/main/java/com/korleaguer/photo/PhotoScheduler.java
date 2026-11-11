package com.korleaguer.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * korleaguer.photos.enabled=true일 때: 앱이 뜨면 뒤에서 한 번, 그 뒤 korleaguer.photos.cron(UTC)마다 사진을 챙긴다
 * (없는 사진 받기, 물어본 지 7일 지난 사진은 바뀌었는지만 묻기). 첫 방문자가 사진을 기다리지 않게 미리 받아 둔다.
 */
@Component
@ConditionalOnProperty(name = "korleaguer.photos.enabled", havingValue = "true")
public class PhotoScheduler {

    private static final Logger log = LoggerFactory.getLogger(PhotoScheduler.class);

    private final PhotoStore store;
    private final ExecutorService executor;

    public PhotoScheduler(PhotoStore store, ExecutorService ingestExecutor) {
        this.store = store;
        this.executor = ingestExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        executor.submit(this::tick);
    }

    @Scheduled(cron = "${korleaguer.photos.cron}", zone = "UTC")
    public void tick() {
        try {
            store.refresh();
        } catch (RuntimeException e) {
            log.warn("photos: refresh failed", e);
        }
    }
}
