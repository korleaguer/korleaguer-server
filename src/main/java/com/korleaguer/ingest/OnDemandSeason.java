package com.korleaguer.ingest;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 선수 페이지를 열었는데 그 선수의 이번 시즌을 아직 받지 않았으면 그 자리에서 한 번 받는다.
 * 짧게(korleaguer.ingest.on-demand-timeout)만 기다리고, 늦거나 실패하면 DB에 있는 것만 보여 준다.
 * 늦은 경우 받기는 뒤에서 계속되고, 끝나면 캐시를 비워 다음 요청부터 보인다.
 * 다른 수집이 도는 중이면 기다리지 않고 바로 돌아간다(그 수집이 받는다).
 * 한 번 받은 시즌은 "받았음" 표시가 있어 다시 열어도 MLB를 부르지 않는다.
 */
@Component
public class OnDemandSeason {

    private static final Logger log = LoggerFactory.getLogger(OnDemandSeason.class);

    private final IngestService ingestService;
    private final PlayerRepository players;
    private final ExecutorService executor;
    private final Duration timeout;

    public OnDemandSeason(IngestService ingestService, PlayerRepository players,
                          @Qualifier("ingestExecutor") ExecutorService executor, KorleaguerProperties properties) {
        this.ingestService = ingestService;
        this.players = players;
        this.executor = executor;
        this.timeout = properties.ingest().onDemandTimeout();
    }

    public void ensureSeason(long playerId) {
        boolean needed = players.findById(playerId)
                .map(p -> p.getMlbId() != null && !ingestService.hasSeason(playerId))
                .orElse(false);
        if (!needed) {
            return;
        }
        Future<?> loading = executor.submit(() -> ingestService.loadPlayerSeason(playerId));
        try {
            loading.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("player {} season is still loading, showing what is in the DB", playerId);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IngestAlreadyRunningException) {
                log.debug("player {} season: another ingest is running", playerId);
            } else {
                log.warn("player {} season load failed", playerId, e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
