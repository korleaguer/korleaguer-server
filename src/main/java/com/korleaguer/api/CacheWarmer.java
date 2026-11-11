package com.korleaguer.api;

import com.korleaguer.ingest.CatchUpFinished;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 따라잡기가 끝나면(CatchUpFinished) 화면이 부를 응답을 미리 계산해 캐시에 넣는다.
 * 운영 장비에서는 홈·선수 응답 하나를 처음 만들 때 1~2초 걸린다. 따라잡기가 12분마다 캐시를 비우므로
 * 사람이 드문 사이트에서는 거의 모든 방문이 빈 캐시를 만나 기다렸다. 미리 채워 두면 사람은 캐시에서 바로 받는다.
 * <ul>
 *   <li>홈(GET /api/home)</li>
 *   <li>선수 페이지 전부(GET /api/players/{id}). 컨트롤러의 시즌 바로 받기(OnDemandSeason)는 거치지 않는다(MLB를 부르지 않는다).</li>
 *   <li>비시즌 홈의 선수 카드(GET /api/my, 명단 전원·7일). 웹(OffseasonPlayers)이 부르는 ids 순서·days와 같아야 캐시가 맞는다.</li>
 * </ul>
 * 하나가 실패해도 나머지는 채운다(실패한 것은 사람이 처음 부를 때 만든다).
 */
@Component
public class CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    /** 웹 비시즌 홈(web의 src/components/home/OffseasonPlayers.tsx, DAYS)이 /api/my에 넘기는 기간 */
    static final int OFFSEASON_CARD_DAYS = 7;

    private final BaseballQueryService queryService;
    private final MyPlayersService myPlayers;

    public CacheWarmer(BaseballQueryService queryService, MyPlayersService myPlayers) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
    }

    @EventListener
    public void onCatchUpFinished(CatchUpFinished event) {
        warm();
    }

    /** 캐시를 채운다. 이미 들어 있는 응답은 다시 만들지 않는다(수집이 비운 뒤에 부른다). */
    public void warm() {
        long started = System.nanoTime();
        int filled = 0;

        HomeResponse home = null;
        try {
            home = queryService.home();
            filled++;
        } catch (RuntimeException e) {
            log.warn("cache warm: home failed", e);
        }

        List<PlayerSummary> players;
        try {
            players = queryService.players();
        } catch (RuntimeException e) {
            log.warn("cache warm: player list failed", e);
            players = List.of();
        }
        for (PlayerSummary player : players) {
            try {
                queryService.player(player.id());
                filled++;
            } catch (RuntimeException e) {
                log.warn("cache warm: player {} failed", player.id(), e);
            }
        }

        if (home != null && home.offseason() != null && !home.offseason().playerIds().isEmpty()) {
            String ids = home.offseason().playerIds().stream().map(String::valueOf).collect(Collectors.joining(","));
            try {
                myPlayers.find(ids, OFFSEASON_CARD_DAYS);
                filled++;
            } catch (RuntimeException e) {
                log.warn("cache warm: off-season cards failed", e);
            }
        }

        log.info("cache warm: filled {} responses in {} ms", filled, (System.nanoTime() - started) / 1_000_000);
    }
}
