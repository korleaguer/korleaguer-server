package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlayerLeaguePositionRepository extends JpaRepository<PlayerLeaguePosition, Long> {

    @Query("""
            select p from PlayerLeaguePosition p
            where p.player.id = :playerId and p.season = :season and p.statGroup = :group
            """)
    Optional<PlayerLeaguePosition> find(long playerId, int season, PlayerSeasonMetric.Group group);

    /** since 이후에 그 시즌 줄을 받은 선수 번호들 */
    @Query("select p.player.id from PlayerLeaguePosition p where p.season = :season and p.fetchedAt >= :since")
    List<Long> findPlayerIdsFetchedSince(int season, Instant since);
}
