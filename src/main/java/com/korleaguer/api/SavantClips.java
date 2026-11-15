package com.korleaguer.api;

import com.korleaguer.news.PoliteFetcher;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공 하나 영상 주소를 Baseball Savant 영상 페이지(sporty-videos?playId=)에서 찾는다.
 * Savant robots.txt는 모두 허용한다(2026-11-15 확인). 예절(robots·1초 간격·실패하면 쉬기)은 PoliteFetcher가 지킨다.
 * 페이지에 mp4(sporty-clips.mlb.com)가 없으면(마이너 경기, 아직 안 올라옴) 빈 값이다.
 */
public class SavantClips {

    static final String PAGE = "https://baseballsavant.mlb.com/sporty-videos?playId=";
    /**
     * 페이지가 주소 안의 '='를 HTML 표기(&#x3D;)로 적는다(2026-11-15 운영에서 확인: "…VA&#x3D;&#x3D;.mp4").
     * 그래서 &·#·; 까지 받고, 찾은 뒤 HTML 표기를 푼다
     */
    private static final Pattern MP4 = Pattern.compile("https://sporty-clips\\.mlb\\.com/[A-Za-z0-9_\\-=/&#;]+?\\.mp4");
    private static final Pattern ENTITY = Pattern.compile("&#(x[0-9A-Fa-f]{1,6}|[0-9]{1,7});|&amp;");
    /** MLB 공 번호는 UUID다. 다른 모양이면 묻지 않는다 */
    static final Pattern PLAY_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final PoliteFetcher fetcher;

    public SavantClips(PoliteFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 영상 주소를 찾는다. 페이지를 받았는데 영상이 없으면 answered=true·빈 주소,
     * 받지 못했으면(실패·robots·쉬는 중) answered=false(저장하지 않고 다음에 다시 묻는다).
     */
    public Lookup find(String playId) {
        if (playId == null || !PLAY_ID.matcher(playId).matches()) {
            return new Lookup(true, Optional.empty());
        }
        PoliteFetcher.Page page = fetcher.getPage(URI.create(PAGE + playId));
        if (!page.ok() || page.body() == null) {
            return new Lookup(false, Optional.empty());
        }
        return new Lookup(true, mp4In(new String(page.body(), StandardCharsets.UTF_8)));
    }

    static Optional<String> mp4In(String html) {
        Matcher matcher = MP4.matcher(html);
        return matcher.find() ? Optional.of(unescape(matcher.group())) : Optional.empty();
    }

    /** "&#x3D;" · "&#61;" · "&amp;" 같은 HTML 표기를 글자로 */
    static String unescape(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1);
            String replacement;
            if (code == null) {
                replacement = "&";
            } else {
                int value = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
                replacement = Character.toString(value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * @param answered 페이지를 받았는지(false면 실패·쉬는 중이라 저장하지 않는다)
     * @param mp4Url   영상 주소
     */
    public record Lookup(boolean answered, Optional<String> mp4Url) {
    }
}
