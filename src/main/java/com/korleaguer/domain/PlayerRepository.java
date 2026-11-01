package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByNameKo(String nameKo);

    Optional<Player> findByBdlPlayerId(Long bdlPlayerId);
}
