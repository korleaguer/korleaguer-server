package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerGameDetailRepository extends JpaRepository<PlayerGameDetail, Long> {

    Optional<PlayerGameDetail> findByPlayerIdAndGamePk(long playerId, long gamePk);
}
