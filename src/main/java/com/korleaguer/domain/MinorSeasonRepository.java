package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MinorSeasonRepository extends JpaRepository<MinorSeason, Long> {

    @Query("select m from MinorSeason m where m.player.id = :playerId and m.season = :season")
    Optional<MinorSeason> find(long playerId, int season);

    @Query("select m from MinorSeason m where m.player.id in :playerIds and m.season = :season")
    List<MinorSeason> findAll(Collection<Long> playerIds, int season);
}
