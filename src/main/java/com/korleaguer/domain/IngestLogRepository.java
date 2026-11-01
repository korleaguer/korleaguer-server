package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface IngestLogRepository extends JpaRepository<IngestLog, Long> {

    Optional<IngestLog> findFirstByKindInAndStatusInOrderByIdDesc(Collection<IngestKind> kinds,
                                                                  Collection<IngestStatus> statuses);
}
