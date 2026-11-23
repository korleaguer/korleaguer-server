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
    void onlyTheBodyBoxIsReadEvenWithoutACopyrightLineAndALeadingArrowLineIsKeptOut() {
        // 리뷰 재현: 본문 칸 안에 저작권 줄이 없고 사이드바·꼬리 칸이 뒤에, 속보 article 카드가 앞에, 본문 첫머리에 ▶ 요약 줄
        ArticlePage page = ArticlePage.parse("""
                <article class="breaking"><div>[속보] 김하성 FA 다년 계약 임박이라는 다른 기사 제목이 여기 길게 있다</div></article>
                <div class="article_content"><div class="summary">▶ 노시환, MLB 포스팅 공식 신청이라는 요약 줄이 맨 앞에 있다</div>
                <p>한화 이글스의 노시환이 메이저리그 진출을 위해 포스팅 시스템을 통한 공식 신청 절차를 마쳤다고 구단이 밝혔다.</p>
                <p>노시환은 30일 동안 메이저리그 30개 구단과 협상할 수 있으며 여러 구단이 관심을 보이는 것으로 알려졌다.</p>
                <p>구단은 선수의 도전을 응원한다며 좋은 결과가 나오기를 바란다고 덧붙였다는 소식이 전해졌다.</p></div>
                <div class="aside"><div>많이 본 뉴스: 류현진, 은퇴 시점을 두고 고민하고 있다는 소식이 전해졌다는 다른 기사</div></div>
                <div class="footer-info">주소: 서울시 어딘가 길 15, 발행인 홍길동 편집인 홍길동 등록번호 어쩌고</div>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).contains("공식 신청 절차를 마쳤다", "여러 구단이 관심", "좋은 결과가 나오기를")
                .doesNotContain("속보", "▶", "류현진", "발행인");
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

    /** Arc XP(조선일보) 꼴: HTML에 문단이 없고 본문이 Fusion.globalContent JSON에 있다. 내용은 지어낸 값 */
    private static final String ARC_PAGE = """
            <html><head>
            <meta property="og:image" content="https://cdn.example.com/arc/roh.jpg" />
            </head><body><div id="fusion-app"><div class="article-body"></div></div>
            <script>window.Fusion=window.Fusion||{};Fusion.arcSite="chosun";Fusion.globalContent={"_id":"X1",\
            "content_elements":[{"type":"image","caption":"사진 설명은 본문이 아니다","url":"https://cdn.example.com/a.jpg"},\
            {"type":"text","content":"[스포츠 기자] 메이저리그 진출에 도전하는 <b>노시환</b>에 대한 관심이 높아지고 있다."},\
            {"type":"text","content":"미국 매체는 노시환이 3루는 물론 1루와 유격수까지 맡을 수 있는 수비가 좋은 선수라고 평가했다."},\
            {"type":"raw_html","content":"<custom-ad><b> ▲ 광고 문구는 본문이 아니다 </b></custom-ad>"},\
            {"type":"text","content":"매체는 &quot;타구를 띄우는 스윙&quot;을 장점으로 꼽았고, 콘택트 비율은 아직 다듬을 부분이라고 짚었다."},\
            {"type":"text","content":"노시환은 올해 초 한화와 다년계약을 맺었고, 포스팅에 성공하면 그 계약은 사라진다."},\
            {"type":"text","content":"Copyright ⓒ 신문사 & example.com 무단 전재 및 재배포 금지"},\
            {"type":"text","content":"저작권 안내 뒤의 글은 읽지 않는다."}]};Fusion.globalContentConfig={"source":"content-api"};</script>
            </body></html>
            """;

    @Test
    void anArcXpPageIsReadFromItsContentJson() {
        // 조선일보: 화면을 자바스크립트로 그려 HTML에 본문 문단이 하나도 없다(11-12, 이슈 #107)
        ArticlePage page = ArticlePage.parse(ARC_PAGE.getBytes(StandardCharsets.UTF_8));

        assertThat(page.imageUrl()).isEqualTo("https://cdn.example.com/arc/roh.jpg");
        assertThat(page.text()).isEqualTo("[스포츠 기자] 메이저리그 진출에 도전하는 노시환 에 대한 관심이 높아지고 있다.\n"
                + "미국 매체는 노시환이 3루는 물론 1루와 유격수까지 맡을 수 있는 수비가 좋은 선수라고 평가했다.\n"
                + "매체는 \"타구를 띄우는 스윙\"을 장점으로 꼽았고, 콘택트 비율은 아직 다듬을 부분이라고 짚었다.\n"
                + "노시환은 올해 초 한화와 다년계약을 맺었고, 포스팅에 성공하면 그 계약은 사라진다.");
    }

    @Test
    void aBrokenArcJsonFallsBackToTheHtml() {
        ArticlePage page = ArticlePage.parse(("<script>Fusion.globalContent={\"content_elements\":[{\"type\":</script>"
                + "<article><p>" + "노시환이 포스팅됐다. ".repeat(12) + "</p></article>").getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).startsWith("노시환이 포스팅됐다.");
    }

    @Test
    void theOutlineOfAPageWithoutABodyTellsWhereTheParagraphsAreWithoutTheirText() {
        // 연합뉴스처럼 우리가 열어 볼 수 없는 사이트는 운영 로그의 이 모양으로 원인을 찾는다(이슈 #107)
        String html = "<html><body><article class=\"top-news\"><p>속보</p></article>"
                + "<div class=\"story-x\" data-title=\"노시환 메츠 계약\" id=\"body1\"><p>" + "본문 첫 문단은 이렇게 길게 이어진다. ".repeat(4)
                + "</p></div></body></html>";

        String outline = ArticlePage.outline(html.getBytes(StandardCharsets.UTF_8));

        assertThat(outline).contains("2 p (1 long, 0 long in article)").contains("1 article (first <article class=\"top-news\">)")
                .contains("box -").contains("first long p #2 in <div class=\"story-x\" id=\"body1\">").contains("first stop p #-1")
                .contains("arc json no")
                .doesNotContain("본문 첫 문단").doesNotContain("메츠 계약"); // 다른 속성(data-title)의 제목도 남기지 않는다
        assertThat(ArticlePage.outline(ARC_PAGE.getBytes(StandardCharsets.UTF_8))).contains("arc json yes");
    }

    /** 긴 문단(LONG_PARAGRAPH 60자 이상)이 되는 지어낸 문장 */
    private static final String LONG = "노시환은 한화의 요청으로 포스팅됐고 30일 동안 메이저리그 구단과 협상할 수 있으며 미국 현지의 관심도 크다고 기사는 전했다. ";

    @Test
    void whenTheBodyIsOutsideEveryArticleBlockTheWholePageIsReadUpToTheCopyrightLine() {
        // 연합뉴스(운영 로그 11-12): article 칸 2개, 긴 문단 12개, 첫 긴 문단은 사진 칸 뒤. 첫 article 칸만 봐서 못 찾았다(#109)
        ArticlePage page = ArticlePage.parse(("<html><body><nav><p>" + LONG + "</p></nav>"
                + "<article class=\"top-news\"><p>속보 한 줄</p></article>"
                + "<div class=\"story-x\"><div class=\"img-con01\"><img src=\"a.jpg\"></div>"
                + "<p>(서울=신문) 기자 = " + LONG + "</p><p>둘째 문단도 길다. " + LONG + "</p><p>셋째 문단. " + LONG + "</p>"
                + "<p class=\"txt-copyright\">저작권자(c) 신문, 무단 전재-재배포 금지</p></div>"
                + "<article class=\"related\"><p>관련 기사 요약은 저작권 줄 뒤라 읽지 않는다. " + LONG + "</p></article>"
                + "<footer><p>" + LONG + "</p></footer></body></html>").getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).startsWith("(서울=신문) 기자 = 노시환은").contains("둘째 문단도 길다.").contains("셋째 문단.")
                .doesNotContain("관련 기사").doesNotContain("저작권자");
        assertThat(page.text().lines()).hasSize(3);
    }

    @Test
    void theBodyInTheSecondArticleBlockIsFound() {
        ArticlePage page = ArticlePage.parse(("<article class=\"promo\"><p>짧은 홍보</p></article>"
                + "<article class=\"story\"><p>첫째. " + LONG + "</p><p>둘째. " + LONG + "</p><p>셋째. " + LONG + "</p></article>")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).startsWith("첫째. 노시환은").contains("셋째.");
    }

    @Test
    void aKnownBodyBoxIsNotWidenedEvenWhenItIsShort() {
        // 알려진 본문 칸이 있는데 짧으면 짧은 기사다. 다른 칸의 긴 글(다른 기사 소개 등)을 본문으로 긁지 않는다
        ArticlePage page = ArticlePage.parse(("<div class=\"article_body\"><p>짧은 본문</p></div>"
                + "<div class=\"other\"><p>" + LONG + "</p><p>" + LONG + "</p><p>" + LONG + "</p></div>")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).doesNotContain("포스팅됐고");
    }

    @Test
    void withoutACopyrightLineTheWholePageIsNotRead() {
        // 본문 끝(저작권 안내 줄)을 알 수 없으면 관련 기사 소개를 본문으로 읽을 수 있다. 그럴 바엔 '본문 없음'이 낫다
        ArticlePage page = ArticlePage.parse(("<article class=\"video\"><p>영상 설명 한 줄</p></article>"
                + "<div class=\"related\"><p>관련 기사 하나. " + LONG + "</p><p>관련 기사 둘. " + LONG + "</p>"
                + "<p>관련 기사 셋. " + LONG + "</p><p>관련 기사 넷. " + LONG + "</p></div>").getBytes(StandardCharsets.UTF_8));

        assertThat(page.text()).doesNotContain("관련 기사");
    }
}
