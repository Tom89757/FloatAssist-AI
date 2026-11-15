package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HtmlTextTest {
    @Test
    void dropsScriptsStylesAndTagsButKeepsBlocks() {
        String html = "<html><head><style>body{color:red}</style><script>var a=1;</script></head>"
                + "<body><h1>标题</h1><p>第一段</p><p>第二段</p></body></html>";

        assertEquals("标题\n第一段\n第二段", HtmlText.toText(html, 1_000));
    }

    @Test
    void decodesNamedAndNumericEntities() {
        String html = "<p>A &amp; B &lt; C &gt; D &#39;E&#39; &quot;F&quot;&nbsp;G</p>";

        assertEquals("A & B < C > D 'E' \"F\" G", HtmlText.toText(html, 1_000));
    }

    @Test
    void breaksOnBrAndKeepsTableCellSeparate() {
        assertEquals("a\nb\nc d", HtmlText.toText("a<br>b<br/>c<td>d</td>", 1_000));
    }

    @Test
    void dropsCommentsAndTruncatesToLimit() {
        assertEquals("可见", HtmlText.toText("<p>可见</p><!-- 隐藏 --><script>x</script>", 1_000));
        assertEquals(10, HtmlText.toText("<p>" + "x".repeat(50) + "</p>", 10).length());
    }
}
