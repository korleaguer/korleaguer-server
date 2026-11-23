package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MinorGameLogRepository extends JpaRepository<MinorGameLog, Long> {

    /** 선수들의 한 시즌 마이너 경기, 선수 순 · 오래된 경기부터 */
    @Query("""
            select g from MinorGameLog g
            where g.player.id in :playerIds and g.season = :season
            order by g.player.id, g.gameDate, g.gamePk
            """)
    List<MinorGameLog> findSeason(Collection<Long> playerIds, int season);

    /** 선수의 마이너 경기 한 개(경기 상세에서 그 경기가 마이너인지·언제였는지 볼 때) */
    Optional<MinorGameLog> findFirstByPlayer_IdAndGamePk(Long playerId, long gamePk);

    /**
     * 마이너 경기 기록 중 굳은(complete) 경기 상세가 아직 없는 것. 마이너 기록은 끝난 경기만 온다.
     * 순서와 굳힐 때가 됐는지는 부르는 쪽이 정한다(시작 시각이 일정 값에 있거나 없어서, ingest.GameDetailIngest)
     */
    @Query("""
            select g from MinorGameLog g
            join fetch g.player p
            where p.mlbId is not null
              and not exists (select d.id from PlayerGameDetail d
                              where d.playerId = p.id and d.gamePk = g.gamePk and d.complete = true)
            """)
    List<MinorGameLog> findWithoutCompleteDetail();

    /** 선수 한 명의 한 시즌 마이너 경기를 모두 지운다(새로 받은 것으로 통째로 바꾸기 전에 부른다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MinorGameLog g where g.player.id = :playerId and g.season = :season")
    int deleteSeason(long playerId, int season);

    /** 마이너 경기 기록이 있는 시즌(연도) */
    @Query("select distinct g.season from MinorGameLog g")
    List<Integer> findSeasons();

    /**
     * 마이너 경기 기록에 나오는 (시즌, 공식 날짜의 연도, 단계). 리그 평균을 받을 조합을 정할 때 쓴다(ingest.LeagueAverageRefresher).
     * 화면 계산이 시즌으로도(성적표·연표), 공식 날짜의 연도로도(내 선수 카드) 리그 평균을 물어 둘 다 준다
     */
    @Query("""
            select distinct new com.korleaguer.domain.MinorSeasonLevel(g.season, year(g.gameDate), g.level)
            from MinorGameLog g
            """)
    List<MinorSeasonLevel> findSeasonLevels();
}
