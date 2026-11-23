package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PlayerSeasonRepository extends JpaRepository<PlayerSeason, Long> {

    @Query("select s from PlayerSeason s where s.player.id = :playerId and s.season = :season")
    Optional<PlayerSeason> find(long playerId, int season);

    @Query("select count(s) > 0 from PlayerSeason s where s.player.id = :playerId and s.season = :season")
    boolean exists(long playerId, int season);
}
