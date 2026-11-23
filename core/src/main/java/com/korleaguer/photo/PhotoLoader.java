package com.korleaguer.photo;

import com.korleaguer.domain.Photo;

import java.util.Optional;

/**
 * 우리가 호스팅하는 사진 한 장을 꺼낸다(GET /api/photos/{이름}). 수집 쪽 ingest.PhotoStore가 구현하고,
 * 화면용 API(api.PhotoController)는 이 인터페이스만 안다(batch와 api는 서로 모른다).
 */
public interface PhotoLoader {

    /** 저장본. 목록(PhotoSources)에 없는 이름이거나 받을 수 없으면 빈 값 */
    Optional<Photo> load(String key);
}
