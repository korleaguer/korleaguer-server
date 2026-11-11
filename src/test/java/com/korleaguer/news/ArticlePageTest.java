package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 기사 페이지에서 대표 사진 주소와 본문 문단만 꺼낸다(공유·댓글 칸은 뺀다). WordPress 꼴, 내용은 지어낸 값 */
class ArticlePageTest {

    private static final String PAGE = """
            <html><head>
            <meta property="og:title" content="KBO's Hanwha Eagles Post Si Hwan Roh" />
            <meta property="og:image" content="https://cdn.example.com/files/roh-1024x681.jpg" />
            </head><body><article>
            <p class="byline">By Writer | at November 1, 2026</p>
            <div class="entry-content">
              <p><strong>TODAY</strong>: Roh has now officially been posted, <a href="https://x.com/a">a reporter</a> reports.</p>
              <div class="ad"></div>
              <p>The window ends at 4pm CT on December 1. Roh&#8217;s status will be resolved before the lockout.</p>
            </div>
            <div class="entry-footer"><p>Share this</p></div>
            <div id="comments"><p>he should stay in korea</p></div>
            </article></body></html>
            """;

    @Test
    void readsOgImageAndBodyParagraphsOnly() {
        ArticlePage page = ArticlePage.parse(PAGE.getBytes(StandardCharsets.UTF_8));

        assertThat(page.imageUrl()).isEqualTo("https://cdn.example.com/files/roh-1024x681.jpg");
        assertThat(page.text()).isEqualTo("TODAY : Roh has now officially been posted, a reporter reports.\n"
                + "The window ends at 4pm CT on December 1. Roh\u2019s status will be resolved before the lockout.");
    }

    @Test
    void theArticleBlockIsFoundByItsClassNotByAMentionInStyles() {
        ArticlePage page = ArticlePage.parse("""
                <head><style>.entry-content p { margin: 0 }</style></head>
                <body><nav><p>Menu</p></nav>
                <div id="post" class="post entry-content clearfix"><p>Body line.</p></div>
                <div class="entry-footer"></div></body>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).isEqualTo("Body line.");
    }

    @Test
    void withoutAnEntryContentBlockLongParagraphsInsideArticleAreUsed() {
        ArticlePage page = ArticlePage.parse("""
                <nav><p>Scores and schedules for every team in the league tonight and tomorrow night</p></nav>
                <article><h1>Roh Posted</h1><p>Short line.</p>
                <p>The Hanwha Eagles posted third baseman Si Hwan Roh, opening a 30-day window for all teams.</p>
                <p>Roh hit 29 homers this season and is expected to draw interest from several clubs.</p></article>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).isEqualTo("The Hanwha Eagles posted third baseman Si Hwan Roh, opening a 30-day window for all teams.\n"
                + "Roh hit 29 homers this season and is expected to draw interest from several clubs.");
    }

    @Test
    void aTitleWithoutPunctuationIsReplacedByThePageTitle() {
        ArticlePage page = ArticlePage.parse("""
                <meta property="og:title" content="노시환·김하성 동반 영입? ML 초대형 입단설 터졌다…야구 깜짝 낭보 나오나 - 엑스포츠뉴스">
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.betterTitle("노시환김하성 동반 영입 ML 초대형 입단설 터졌다 야구 깜짝 낭보 나오나"))
                .isEqualTo("노시환·김하성 동반 영입? ML 초대형 입단설 터졌다…야구 깜짝 낭보 나오나");
        assertThat(page.betterTitle("전혀 다른 기사 제목")).isNull();
        assertThat(page.betterTitle("노시환")).isNull();
        // 피드 제목에 부호가 더 많으면(페이지 제목이 부호를 뺀 경우) 바꾸지 않는다
        ArticlePage plain = ArticlePage.parse("<meta property=\"og:title\" content=\"노시환 좋은 소식 기다린다\">"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(plain.betterTitle("노시환 \"좋은 소식 기다린다\"")).isNull();
        assertThat(page.betterTitle("노시환·김하성 동반 영입? ML 초대형 입단설 터졌다…야구 깜짝 낭보 나오나")).isNull();
    }

    @Test
    void aShortenedSitemapTitleGetsTheFullPageTitleAndTwitterImageIsAPhoto() {
        // 엑스포츠뉴스 실제 꼴: 사이트맵은 부호·한자를 빼고 앞부분만, 페이지는 og:image 없이 twitter:image
        ArticlePage page = ArticlePage.parse("""
                <meta property="og:title" content="노시환-김하성 동반 영입! ML 초대형 입단설 터졌다…韓 야구 깜짝 낭보 나오나 &quot;KIM 데려오면 ROH 확보에 도움&quot;" >
                <meta name="twitter:image" content="https://image.example.kr/1794383296653257.webp">
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.betterTitle("노시환김하성 동반 영입 ML 초대형 입단설 터졌다 야구 깜짝 낭보 나오나"))
                .isEqualTo("노시환-김하성 동반 영입! ML 초대형 입단설 터졌다…韓 야구 깜짝 낭보 나오나 \"KIM 데려오면 ROH 확보에 도움\"");
        assertThat(page.imageUrl()).isEqualTo("https://image.example.kr/1794383296653257.webp");
    }

    @Test
    void aSiteNameTailIsNeverAddedToATitle() {
        // 리뷰 재현: 꼬리를 뗀 본제목이 맞지 않는다고 꼬리 붙은 전체 제목으로 넘어가면 안 된다
        assertThat(titleFrom("노시환 좋은 소식 기다린다 - 스포츠조선").betterTitle("노시환 \"좋은 소식 기다린다\"")).isNull();
        assertThat(titleFrom("노시환 '포스팅 개시' 공식 발표 | MK스포츠").betterTitle("노시환 \"포스팅 개시\" 공식 발표")).isNull();
        // 경로식 og:title(스포츠Q)에 온전한 피드 제목이면 그대로
        assertThat(titleFrom("노시환, 컵스 숨은 표적 美 매체 조명 < 해외야구 < 야구 < 기사본문 - 스포츠Q")
                .betterTitle("노시환, 컵스 숨은 표적 美 매체 조명")).isNull();
        // 한자만 빠진 사이트맵 제목은 한자가 있는 본제목으로(꼬리 없이)
        assertThat(titleFrom("美 매체 노시환 3년 계약 전망 - 스포티비뉴스").betterTitle("매체 노시환 3년 계약 전망"))
                .isEqualTo("美 매체 노시환 3년 계약 전망");
    }

    @Test
    void aBodyWrittenWithLineBreaksIsRead() {
        // 엑스포츠뉴스 꼴: p 없이 <br>로 나뉜 본문. 짧은 줄(날짜·메뉴)과 스크립트는 뺀다
        ArticlePage page = ArticlePage.parse("""
                <script>var x = "아주 긴 스크립트 문자열이 본문으로 섞이면 안 되는데 이렇게 길게 써 둔다";</script>
                <div>기사입력 2026.11.11</div>
                <div class="news">(엑스포츠뉴스 이우진 기자) 미국 메이저리그(MLB) 필라델피아 필리스가 김하성의 영입에 관심을 보이고 있다.<br><br>
                필라델피아가 김하성을 데려오면 노시환 영입에도 도움이 될 것이라는 현지 매체의 분석이 나왔다.<br>
                노시환은 이번 겨울 포스팅 시스템으로 메이저리그 진출을 노리고 있으며 여러 구단의 관심을 받고 있다.</div>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).contains("필라델피아 필리스가 김하성의 영입에 관심", "노시환 영입에도 도움", "여러 구단의 관심")
                .doesNotContain("스크립트", "기사입력");
    }

    @Test
    void theBodyBoxIsReadAndRelatedHeadlinesAndCopyrightAreLeftOut() {
        // 엑스포츠뉴스 꼴: 제목·사이드바가 본문 칸 밖에, 본문 끝에 ▶ 관련 기사와 저작권 줄
        ArticlePage page = ArticlePage.parse("""
                <div class="aside"><div>많이 본 뉴스: 김하성, 애틀랜타와 재계약 협상 중이라는 현지 보도가 나왔다고 한다</div></div>
                <div class="news_contents">(엑스포츠뉴스 이우진 기자) 미국 메이저리그(MLB) 필라델피아 필리스가 김하성의 영입에 관심을 보이고 있다.<br>
                필라델피아가 김하성을 데려오면 노시환 영입에도 도움이 될 것이라는 현지 매체의 분석이 나왔다.<br>
                사진=연합뉴스 / X / 엑스포츠뉴스DB 사진 설명이 길게 들어간 줄도 본문으로 넣지 않는다<br>
                이우진 기자 wzyfooty@xportsnews.com</div>
                <ul class="another_ats"><li>▶ KBO 45년史 최초 홀드왕, NPB 우승팀 파격 제안→결별 확정이라는 다른 기사 제목</li></ul>
                <div class="copyright">ⓒ 엑스포츠뉴스 무단전재 및 재배포 금지 / 서울시 어딘가 주소와 발행인 이름이 길게 이어진다</div>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).contains("필라델피아 필리스가 김하성의 영입", "노시환 영입에도 도움")
                .doesNotContain("많이 본 뉴스", "홀드왕", "무단전재", "사진=");
    }

    @Test
    void ogImageWinsOverTwitterImage() {
        ArticlePage page = ArticlePage.parse("""
                <meta name="twitter:image" content="https://cdn.example.kr/logo.png">
                <meta property="og:image" content="https://cdn.example.kr/photo.jpg">
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.imageUrl()).isEqualTo("https://cdn.example.kr/photo.jpg");
    }

    private static ArticlePage titleFrom(String ogTitle) {
        return ArticlePage.parse(("<meta property=\"og:title\" content=\"" + ogTitle + "\">").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anEucKrPageIsReadInItsOwnCharset() {
        byte[] html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=euc-kr\">"
                .concat("<meta property=\"og:description\" content=\"노시환이 협상을 시작했다.\"></head></html>")
                .getBytes(java.nio.charset.Charset.forName("EUC-KR"));

        assertThat(ArticlePage.parse(html).description()).isEqualTo("노시환이 협상을 시작했다.");
    }

    @Test
    void noArticleBlockMeansNoBodyAndHttpImagesBecomeHttps() {
        ArticlePage page = ArticlePage.parse("""
                <meta content="http://cdn.example.com/x.jpg" property="og:image">
                <meta name="description" content="한 줄 &amp; 소개"><p>just a page</p>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.imageUrl()).isEqualTo("https://cdn.example.com/x.jpg");
        assertThat(page.description()).isEqualTo("한 줄 & 소개");
        assertThat(page.text()).isEmpty();
    }
}
