package com.korleaguer.api;

/** 없는 기사 번호이거나, 지금 받지 않는 언론사(news/sources.yml에서 뺀 곳)의 기사다. 404로 답한다 */
public class NewsNotFoundException extends RuntimeException {
}
