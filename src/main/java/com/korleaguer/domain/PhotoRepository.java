package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 기본키는 사진 이름(mlb-808982, kbo-77829-2019)이다. */
public interface PhotoRepository extends JpaRepository<Photo, String> {

    /** 사진 이름만(본문 blob은 읽지 않는다) */
    @Query("select p.photoKey from Photo p")
    List<String> findAllKeys();

    /** 사진 이름과 본문 해시(sha256) 쌍. 본문 blob은 읽지 않는다 */
    @Query("select p.photoKey, p.sha256 from Photo p")
    List<Object[]> findAllHashes();
}
