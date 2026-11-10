package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** 기본키는 시즌 연도다. */
public interface SeasonDatesRepository extends JpaRepository<SeasonDates, Integer> {
}
