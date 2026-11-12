package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerSeasonMetricRepository extends JpaRepository<PlayerSeasonMetric, Long> {

    @Query("""
            select m from PlayerSeasonMetric m
            where m.player.id = :playerId and m.season = :season and m.statGroup = :group
            """)
    Optional<PlayerSeasonMetric> find(long playerId, int season, PlayerSeasonMetric.Group group);

    /** 선수들의 fromSeason ~ toSeason(양 끝 포함) 줄 */
    @Query("""
            select m from PlayerSeasonMetric m
            where m.player.id in :playerIds and m.season between :fromSeason and :toSeason
            """)
    List<PlayerSeasonMetric> findSeasons(Collection<Long> playerIds, int fromSeason, int toSeason);

    /** since 이후에 그 시즌 줄을 받은 선수 번호들 */
    @Query("select m.player.id from PlayerSeasonMetric m where m.season = :season and m.fetchedAt >= :since")
    List<Long> findPlayerIdsFetchedSince(int season, Instant since);
}
