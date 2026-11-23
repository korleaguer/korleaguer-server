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

    /** 선수 한 명의 한 시즌 마이너 경기를 모두 지운다(새로 받은 것으로 통째로 바꾸기 전에 부른다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MinorGameLog g where g.player.id = :playerId and g.season = :season")
    int deleteSeason(long playerId, int season);

    /** 마이너 경기 기록이 있는 시즌(연도) */
    @Query("select distinct g.season from MinorGameLog g")
    List<Integer> findSeasons();
}
