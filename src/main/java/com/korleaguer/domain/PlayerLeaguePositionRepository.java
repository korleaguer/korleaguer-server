package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerLeaguePositionRepository extends JpaRepository<PlayerLeaguePosition, Long> {

    @Query("""
            select p from PlayerLeaguePosition p
            where p.player.id = :playerId and p.season = :season and p.statGroup = :group
            """)
    Optional<PlayerLeaguePosition> find(long playerId, int season, PlayerSeasonMetric.Group group);

    /** 선수들의 그 시즌 줄(타자·투수 묶음 모두). 시즌 성적표가 명단 전원을 한 번에 읽는다 */
    @Query("""
            select p from PlayerLeaguePosition p
            where p.player.id in :playerIds and p.season = :season
            """)
    List<PlayerLeaguePosition> findSeason(Collection<Long> playerIds, int season);

    /** since 이후에 그 시즌 줄을 받은 선수 번호들 */
    @Query("select p.player.id from PlayerLeaguePosition p where p.season = :season and p.fetchedAt >= :since")
    List<Long> findPlayerIdsFetchedSince(int season, Instant since);
}
