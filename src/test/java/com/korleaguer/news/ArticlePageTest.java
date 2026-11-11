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
    void noArticleBlockMeansNoBodyAndPlainHttpImagesAreIgnored() {
        ArticlePage page = ArticlePage.parse("""
                <meta content="http://cdn.example.com/x.jpg" property="og:image"><p>just a page</p>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.imageUrl()).isNull();
        assertThat(page.text()).isEmpty();
    }
}
