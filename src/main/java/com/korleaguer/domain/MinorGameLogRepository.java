package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface MinorGameLogRepository extends JpaRepository<MinorGameLog, Long> {

    /** 선수들의 한 시즌 마이너 경기, 선수 순 · 오래된 경기부터 */
    @Query("""
            select g from MinorGameLog g
            where g.player.id in :playerIds and g.season = :season
            order by g.player.id, g.gameDate, g.gamePk
            """)
    List<MinorGameLog> findSeason(Collection<Long> playerIds, int season);

    /** 선수 한 명의 한 시즌 마이너 경기를 모두 지운다(새로 받은 것으로 통째로 바꾸기 전에 부른다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MinorGameLog g where g.player.id = :playerId and g.season = :season")
    int deleteSeason(long playerId, int season);
}
