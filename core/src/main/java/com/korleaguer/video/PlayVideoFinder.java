package com.korleaguer.video;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 공 하나 영상 주소를 찾는다. 화면용 API가 요청 중에 밖을 부르는 단 하나의 예외라서 api는 이 인터페이스 하나만 안다
 * ('문 하나', ARCHITECTURE.md §3). 구현은 client 모듈(client.SavantClips, Baseball Savant 영상 페이지)이다.
 */
public interface PlayVideoFinder {

    /** MLB 공 번호는 UUID다. 다른 모양이면 묻지 않는다 */
    Pattern PLAY_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * 영상 주소를 찾는다. 페이지를 받았는데 영상이 없으면 answered=true·빈 주소,
     * 받지 못했으면(실패·robots·쉬는 중) answered=false(저장하지 않고 다음에 다시 묻는다).
     */
    Lookup find(String playId);

    /**
     * @param answered 페이지를 받았는지(false면 실패·쉬는 중이라 저장하지 않는다)
     * @param mp4Url   영상 주소
     */
    record Lookup(boolean answered, Optional<String> mp4Url) {
    }
}
