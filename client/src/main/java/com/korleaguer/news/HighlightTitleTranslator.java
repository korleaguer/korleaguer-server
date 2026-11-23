package com.korleaguer.news;

import java.util.Optional;

/** 하이라이트 영상의 영어 제목을 한국어 한 줄로 옮긴다(이슈 #199). API 키가 없으면 OFF(아무것도 하지 않음)다. */
public interface HighlightTitleTranslator {

    HighlightTitleTranslator OFF = new HighlightTitleTranslator() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Optional<String> translate(String titleEn, String playerKo, String playerEn) {
            return Optional.empty();
        }

        @Override
        public String version() {
            return null;
        }
    };

    boolean enabled();

    /**
     * @param playerKo 영상이 뜨는 창의 선수 한글 이름(제목에서 이 선수 이름은 뺀다)
     * @param playerEn 그 선수의 영어 이름(제목에 적힌 꼴을 알아보게)
     * @return 한국어 제목. 거절·빈 답·못 쓸 답이면 empty
     * @throws Summarizer.Failure 서비스가 답하지 못했을 때(키·결제·사용량·장애)
     */
    Optional<String> translate(String titleEn, String playerKo, String playerEn);

    /** 옮긴 모델과 지시문 판(예: claude-haiku-5-5/t1). 제목마다 적어 두고, 다른 판으로 옮긴 옛 번역은 다시 옮긴다 */
    String version();
}
