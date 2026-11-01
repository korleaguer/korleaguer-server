package com.korleaguer.client;

import java.time.LocalDate;
import java.util.List;

/**
 * balldontlie MLB API에서 필요한 호출만 모은 인터페이스.
 * 실제 HTTP 구현(HttpBdlClient)과 샘플 구현(SampleBdlClient)이 같은 모양으로 답한다.
 * 페이지 넘기기는 구현 안에서 끝내고 전체 목록을 돌려준다.
 */
public interface BdlClient {

    List<BdlPlayer> searchPlayers(String search);

    /** id로 선수 한 명. 없으면 null */
    BdlPlayer getPlayer(long id);

    /** 빈 목록인 조건은 걸지 않는다. */
    List<BdlGame> getGames(List<Long> teamIds, List<Integer> seasons, List<LocalDate> dates);

    /**
     * 빈 목록인 조건은 걸지 않는다.
     *
     * @throws StatsUnavailableException 요금제 때문에 기록을 받을 수 없을 때(401/403)
     */
    List<BdlStats> getStats(List<Long> playerIds, List<Integer> seasons, List<Long> gameIds);

    /** 지금까지 보낸 요청 수(수집 기록용) */
    long requestCount();
}
