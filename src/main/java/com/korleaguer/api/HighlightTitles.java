package com.korleaguer.api;

import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 하이라이트 영어 제목의 한국어 번역(이슈 #199). 저장된 번역(highlight_title)만 읽는다(이슈 #237, ARCHITECTURE.md §3).
 * 번역은 수집(ingest.HighlightTitleIngest)이 미리 해 둔다. 여기서는 AI를 부르지 않고, 아직 없는 제목은 빠진다(titleKo는 null).
 * <ul>
 *   <li>같은 영어 제목은 DB에 한 줄이고 모든 선수·경기가 다시 쓴다.</li>
 *   <li>번역 규칙 판이 낡은 번역도 그대로 보여 준다(수집이 다시 옮기면 바뀐다).</li>
 * </ul>
 * 경기 상세 JSON(player_game_detail)에는 한국어 제목을 넣지 않고 응답을 줄 때마다 붙이므로, 번역이 늦게 생겨도 굳은
 * 상세에 바로 반영된다.
 */
@Service
public class HighlightTitles {

    private final HighlightTitleRepository titles;

    public HighlightTitles(HighlightTitleRepository titles) {
        this.titles = titles;
    }

    /** 저장된 한국어 제목(영어 제목 → 한국어). 아직 번역이 없는 제목은 빠진다 */
    public Map<String, String> koreanOf(Collection<String> titlesEn) {
        List<String> wanted = titlesEn.stream().filter(t -> t != null && !t.isBlank()).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, String> known = new HashMap<>();
        for (HighlightTitle row : titles.findByTitleEnIn(wanted)) {
            known.put(row.getTitleEn(), row.getTitleKo());
        }
        return known;
    }
}
