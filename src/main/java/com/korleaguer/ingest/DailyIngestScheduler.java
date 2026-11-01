package com.korleaguer.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** korleaguer.ingest.schedule-enabled=true 일 때만 켜지는 하루치 수집 스케줄 */
@Component
@ConditionalOnProperty(name = "korleaguer.ingest.schedule-enabled", havingValue = "true")
public class DailyIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyIngestScheduler.class);

    private final IngestService ingestService;
    private final Clock clock;

    public DailyIngestScheduler(IngestService ingestService, Clock clock) {
        this.ingestService = ingestService;
        this.clock = clock;
    }

    @Scheduled(cron = "${korleaguer.ingest.cron}", zone = "UTC")
    public void ingestToday() {
        try {
            ingestService.ingestDaily(LocalDate.now(clock));
        } catch (IngestAlreadyRunningException e) {
            log.info("skip scheduled daily ingest: another ingest is running");
        }
    }
}
