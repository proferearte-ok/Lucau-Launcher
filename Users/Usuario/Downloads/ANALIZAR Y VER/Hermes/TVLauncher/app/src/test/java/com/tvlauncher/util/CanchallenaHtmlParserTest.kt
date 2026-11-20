package com.tvlauncher.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CanchallenaHtmlParserTest {

    private fun getResource(name: String): String {
        val stream = javaClass.getResourceAsStream("/$name")
            ?: throw AssertionError("Test resource not found: /$name")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `parseFixturePage extracts matches from fixture-card`() {
        val doc: Document = Jsoup.parse(getResource("fixtures/canchallena_fixture.html"))
        val matches = CanchallenaHtmlParser.parseFixturePage(doc, "torneo-clausura")

        assertEquals(2, matches.size)

        val m1 = matches[0]
        assertEquals("River Plate", m1.homeTeam)
        assertEquals("Boca Juniors", m1.awayTeam)
        assertEquals(LocalDate.of(LocalDate.now().year, 11, 19), m1.date)
        assertEquals(LocalTime.of(21, 30), m1.time)
        assertEquals("ESPN", m1.tvChannel)
        assertTrue(m1.url.contains("river-boca"))
        assertEquals("Torneo Clausura 2026", m1.competition)

        val m2 = matches[1]
        assertEquals("Real Madrid", m2.homeTeam)
        assertEquals("Barcelona", m2.awayTeam)
        assertEquals("Star+", m2.tvChannel)
    }

    @Test
    fun `parseFixturePage returns empty when no fixture cards`() {
        val doc: Document = Jsoup.parse(getResource("fixtures/canchallena_empty.html"))
        val matches = CanchallenaHtmlParser.parseFixturePage(doc, "la-liga")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `normalizeChannelName maps known channels`() {
        assertEquals("ESPN", CanchallenaHtmlParser.normalizeChannelName("ESPN"))
        assertEquals("Star+", CanchallenaHtmlParser.normalizeChannelName("Star Plus"))
        assertEquals("TyC Sports", CanchallenaHtmlParser.normalizeChannelName("TyC Sports"))
    }

    @Test
    fun `cleanTeamName removes brackets and extra spaces`() {
        assertEquals("River Plate", CanchallenaHtmlParser.cleanTeamName("River  Plate  [1]"))
    }

    @Test
    fun `extractTvChannel reads img alt`() {
        val card: Element = Jsoup.parse(
            "<div class='tv-badge'><img alt='ESPN' src='/e.png'></div>"
        ).selectFirst(".tv-badge")!!
        val channel = CanchallenaHtmlParser.extractTvChannel(card)
        assertEquals("ESPN", channel)
    }

    @Test
    fun `extractMatchUrl resolves relative href`() {
        val card: Element = Jsoup.parse(
            "<div class='fixture-card'><a href='/futbol/x/1'>Ver</a></div>"
        ).selectFirst(".fixture-card")!!
        val url = CanchallenaHtmlParser.extractMatchUrl(card)
        assertEquals("https://canchallena.lanacion.com.ar/futbol/x/1", url)
    }

    @Test
    fun `parseDateTimeText parses date and time`() {
        val (date, time) = CanchallenaHtmlParser.parseDateTimeText("19/11 21:30")!!
        assertEquals(LocalTime.of(21, 30), time)
        assertEquals(19, date.dayOfMonth)
        assertEquals(11, date.monthValue)
    }

    @Test
    fun `toScheduleEvents filters today and tomorrow only`() {
        val today = LocalDate.now()
        val now = LocalTime.of(10, 0)

        val matches = listOf(
            CanchallenaHtmlParser.ParsedMatch(today, LocalTime.of(11, 0), "A", "B", "Liga", "ESPN", ""),
            CanchallenaHtmlParser.ParsedMatch(today.plusDays(1), LocalTime.of(20, 0), "C", "D", "Liga", null, ""),
            CanchallenaHtmlParser.ParsedMatch(today.minusDays(1), LocalTime.of(12, 0), "E", "F", "Liga", null, ""),
        )

        val events = CanchallenaHtmlParser.toScheduleEvents(matches, today, now)

        assertEquals(2, events.size) // yesterday filtered out
        val todayEvent = events.find { it.date == today }!!
        assertTrue(todayEvent.isUpcoming)
        assertFalse(todayEvent.isPast)
        val tomorrowEvent = events.find { it.date == today.plusDays(1) }!!
        assertFalse(tomorrowEvent.isLive)
    }
}
