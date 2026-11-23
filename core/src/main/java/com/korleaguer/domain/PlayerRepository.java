package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByNameKo(String nameKo);

    Optional<Player> findByMlbId(Long mlbId);

    /** MLB 선수 번호가 있는 선수들 */
    List<Player> findByMlbIdIsNotNull();
}
