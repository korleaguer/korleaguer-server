package com.korleaguer.news;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** GET 한 번(리다이렉트는 따라가지 않는다). 테스트에서 가짜로 바꿔 끼운다. */
public interface HttpGetter {

    /**
     * @param headers 더 보낼 헤더(조건부 요청 등). User-Agent는 구현이 붙인다
     * @throws IOException 연결 실패·시간 초과
     */
    Reply get(URI uri, Map<String, String> headers) throws IOException;

    /**
     * @param headers 응답 헤더(이름은 소문자, 값은 첫 번째 것)
     * @param body    본문(크기 제한을 넘으면 앞부분만)
     */
    record Reply(int status, Map<String, String> headers, byte[] body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
