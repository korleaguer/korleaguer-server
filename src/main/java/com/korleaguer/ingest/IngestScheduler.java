package com.korleaguer.ingest;

import com.korleaguer.support.KoreanTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * korleaguer.ingest.schedule-enabled=true일 때 12분마다(korleaguer.ingest.cron) 깨어나 필요한 것만 받는다.
 * <ul>
 *   <li>늘: 오늘 안 받은 선수 정보·이동 기록(하루 한 번), 아직 안 받은 선수 시즌 백필</li>
 *   <li>시즌 중(앞뒤 3일 안에 우리 팀 경기가 있음): 경기 시간대(한국 시간 01:00~16:00)에는 깨어날 때마다,
 *       그 밖에는 매시 정각 근처(분이 12 미만)에만 오늘·어제 경기 갱신(밀렸으면 밀린 날부터)과 끝난 경기 재확인</li>
 *   <li>비시즌: 경기 갱신은 하지 않고, 하루(UTC 날짜) 한 번 앞으로 7일 일정만 받는다(1번).
 *       포스트시즌 경기는 그날 일정을 받을 때에야 DB에 들어오므로, 라운드 사이 쉬는 날이 길어 비시즌으로 넘어가도
 *       여기서 다음 경기를 보고 시즌 중으로 돌아온다.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "korleaguer.ingest.schedule-enabled", havingValue = "true")
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestService ingestService;
    private final Clock clock;

    public IngestScheduler(IngestService ingestService, Clock clock) {
        this.ingestService = ingestService;
        this.clock = clock;
    }

    @Scheduled(cron = "${korleaguer.ingest.cron}", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        try {
            boolean inSeason = ingestService.inSeason(now);
            ingestService.catchUp(inSeason && gameTime(now), !inSeason);
        } catch (IngestAlreadyRunningException e) {
            log.info("skip scheduled ingest: another ingest is running");
        }
    }

    /** 경기를 갱신할 차례인지: 한국 시간 01:00~16:00이면 늘, 그 밖에는 정각 근처(분이 12 미만)만 */
    static boolean gameTime(Instant now) {
        ZonedDateTime kst = now.atZone(KoreanTime.SEOUL);
        boolean busyHours = kst.getHour() >= 1 && kst.getHour() < 16;
        return busyHours || kst.getMinute() < 12;
    }
}
