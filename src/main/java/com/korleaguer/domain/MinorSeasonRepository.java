package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MinorSeasonRepository extends JpaRepository<MinorSeason, Long> {

    @Query("select m from MinorSeason m where m.player.id = :playerId and m.season = :season")
    Optional<MinorSeason> find(long playerId, int season);

    @Query("select m from MinorSeason m where m.player.id in :playerIds and m.season = :season")
    List<MinorSeason> findAll(Collection<Long> playerIds, int season);

    /** since 이후에 그 시즌 마이너 경기 수를 받은 선수 번호들 */
    @Query("select m.player.id from MinorSeason m where m.season = :season and m.fetchedAt >= :since")
    List<Long> findPlayerIdsFetchedSince(int season, Instant since);
}
