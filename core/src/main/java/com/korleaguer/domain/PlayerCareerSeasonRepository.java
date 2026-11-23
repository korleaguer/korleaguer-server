package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlayerCareerSeasonRepository extends JpaRepository<PlayerCareerSeason, Long> {

    /** 선수의 한 묶음 시즌 줄 전부(최신 시즌부터) */
    @Query("""
            select c from PlayerCareerSeason c
            where c.player.id = :playerId and c.statGroup = :group
            order by c.season desc
            """)
    List<PlayerCareerSeason> findNewestFirst(long playerId, PlayerSeasonMetric.Group group);
}
