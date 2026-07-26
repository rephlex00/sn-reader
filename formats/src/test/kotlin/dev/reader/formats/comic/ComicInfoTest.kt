package dev.reader.formats.comic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComicInfoTest {
    @Test fun `parses series number title writer`() {
        val info = parseComicInfo(
            """<?xml version="1.0"?><ComicInfo>
               <Series>Berserk</Series><Number>3</Number>
               <Title>The Guardians</Title><Writer>Kentaro Miura</Writer></ComicInfo>""",
        )
        assertThat(info.series).isEqualTo("Berserk")
        assertThat(info.number).isEqualTo("3")
        assertThat(info.title).isEqualTo("The Guardians")
        assertThat(info.writer).isEqualTo("Kentaro Miura")
    }

    @Test fun `Manga YesAndRightToLeft means right to left`() {
        assertThat(parseComicInfo("<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>").rightToLeft).isTrue()
    }

    @Test fun `Manga Yes is left to right`() {
        assertThat(parseComicInfo("<ComicInfo><Manga>Yes</Manga></ComicInfo>").rightToLeft).isFalse()
    }

    @Test fun `absent Manga leaves direction unknown`() {
        assertThat(parseComicInfo("<ComicInfo><Series>X</Series></ComicInfo>").rightToLeft).isNull()
    }

    @Test fun `BlackAndWhite Yes is recognised`() {
        assertThat(parseComicInfo("<ComicInfo><BlackAndWhite>Yes</BlackAndWhite></ComicInfo>").blackAndWhite).isTrue()
    }

    @Test fun `malformed xml yields all-null, never throws`() {
        val info = parseComicInfo("this is not xml <<<")
        assertThat(info.series).isNull()
        assertThat(info.rightToLeft).isNull()
    }
}
