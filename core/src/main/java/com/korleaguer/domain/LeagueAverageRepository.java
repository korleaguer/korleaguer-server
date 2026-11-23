package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeagueAverageRepository extends JpaRepository<LeagueAverage, Long> {

    /** 그 시즌 그 단계의 줄. 아직 받지 못했으면 비어 있다 */
    Optional<LeagueAverage> findBySeasonAndLevel(int season, String level);
}
