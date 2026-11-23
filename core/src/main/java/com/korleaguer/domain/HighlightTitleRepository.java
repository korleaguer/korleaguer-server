package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HighlightTitleRepository extends JpaRepository<HighlightTitle, Long> {

    List<HighlightTitle> findByTitleEnIn(Collection<String> titles);

    Optional<HighlightTitle> findByTitleEn(String title);
}
