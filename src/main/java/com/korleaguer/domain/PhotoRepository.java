package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** 기본키는 사진 이름(mlb-808982, kbo-si-hwan-roh)이다. */
public interface PhotoRepository extends JpaRepository<Photo, String> {
}
