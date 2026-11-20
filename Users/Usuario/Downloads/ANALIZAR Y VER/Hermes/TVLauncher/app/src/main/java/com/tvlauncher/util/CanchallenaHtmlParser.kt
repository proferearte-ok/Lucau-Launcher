package com.tvlauncher.util

import android.content.Context
import android.util.Log
import com.tvlauncher.model.ScheduleEvent
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.IOException
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

object CanchallenaHtmlParser {
    private const val TAG = "CanchallenaParser"
    private const val BASE_URL = "https://canchallena.lanacion.com.ar"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36"
    
    // Competitions to scrape with their fixture URLs
    private val COMPETITIONS = mapOf(
        "torneo-clausura" to "futbol/torneo-clausura/fixture/",
        "copa-libertadores" to "futbol/copa-libertadores/fixture/",
        "la-liga" to "futbol/la-liga/fixture/",
        "formula-1" to "automovilismo/formula-1-2026/gran-premio-usa-21/" // F1 current GP
    )
    
    private val DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("dd/MM"),
        DateTimeFormatter.ofPattern("d/M"),
        DateTimeFormatter.ofPattern("EEEE dd/MM", java.util.Locale.getDefault()),
        DateTimeFormatter.ofPattern("EEE dd/MM", java.util.Locale.getDefault())
    )
    
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    
    data class ParsedMatch(
        val date: LocalDate,
        val time: LocalTime?,
        val homeTeam: String,
        val awayTeam: String,
        val competition: String,
        val tvChannel: String?,
        val url: String
    )
    
    /**
     * Fetch and parse all configured competitions
     */
    fun fetchAllMatches(context: Context): List<ParsedMatch> {
        val allMatches = mutableListOf<ParsedMatch>()
        
        COMPETITIONS.forEach { (compKey, fixturePath) ->
            try {
                val url = "$BASE_URL/$fixturePath"
                Log.d(TAG, "Fetching $compKey from $url")
                
                val doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(10000)
                    .followRedirects(true)
                    .get()
                
                val matches = parseFixturePage(doc, compKey)
                allMatches.addAll(matches)
                Log.d(TAG, "Parsed ${matches.size} matches from $compKey")
                
            } catch (e: IOException) {
                Log.w(TAG, "Network error fetching $compKey: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error parsing $compKey", e)
            }
        }
        
        return allMatches
    }
    
    /**
     * Parse a single fixture page
     */
    internal fun parseFixturePage(doc: Document, competitionKey: String): List<ParsedMatch> {
        val matches = mutableListOf<ParsedMatch>()
        
        // Try multiple selector strategies (site may vary)
        val fixtureCards = trySelectors(doc, listOf(
            ".fixture-card",
            ".match-card", 
            ".event-card",
            "[data-testid='fixture-card']",
            ".match-item",
            ".fixture-item"
        ))
        
        if (fixtureCards.isEmpty()) {
            Log.w(TAG, "No fixture cards found for $competitionKey with known selectors")
            // Fallback: try to find any link with match-like structure
            return parseFallback(doc, competitionKey)
        }
        
        fixtureCards.forEach { card ->
            try {
                val parsed = parseMatchCard(card, competitionKey)
                parsed?.let { matches.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing match card", e)
            }
        }
        
        return matches
    }
    
    internal fun trySelectors(doc: Document, selectors: List<String>): Elements {
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                Log.d(TAG, "Found ${elements.size} cards with selector: $selector")
                return elements
            }
        }
        return Elements.empty()
    }
    
    internal fun parseMatchCard(card: Element, competitionKey: String): ParsedMatch? {
        // Extract teams
        val homeTeam = extractTeam(card, ".team-home", ".local-team", ".home-team", "[data-team='home']")
        val awayTeam = extractTeam(card, ".team-away", ".visitor-team", ".away-team", "[data-team='away']")
        
        if (homeTeam.isBlank() || awayTeam.isBlank()) {
            return null
        }
        
        // Extract date/time
        val (date, time) = extractDateTime(card)
        
        // Extract TV channel
        val tvChannel = extractTvChannel(card)
        
        // Extract URL
        val url = extractMatchUrl(card)
        
        return ParsedMatch(
            date = date,
            time = time,
            homeTeam = cleanTeamName(homeTeam),
            awayTeam = cleanTeamName(awayTeam),
            competition = formatCompetitionName(competitionKey),
            tvChannel = tvChannel,
            url = url
        )
    }
    
    internal fun extractTeam(card: Element, vararg selectors: String): String {
        for (selector in selectors) {
            val element = card.selectFirst(selector)
            if (element != null) {
                val text = element.text().trim()
                if (text.isNotBlank()) return text
            }
        }
        // Fallback: try to find team names in any element with team-like classes
        val teamElements = card.select("[class*='team'], [class*='equipo']")
        if (teamElements.size >= 2) {
            return teamElements[0].text().trim()
        }
        return ""
    }
    
    internal fun extractDateTime(card: Element): Pair<LocalDate, LocalTime?> {
        // Try multiple date/time selectors
        val dateTimeSelectors = listOf(
            ".match-time",
            ".match-date", 
            ".fixture-date",
            ".event-time",
            "[data-date]",
            "time[datetime]"
        )
        
        for (selector in dateTimeSelectors) {
            val element = card.selectFirst(selector)
            if (element != null) {
                val datetimeAttr = element.attr("datetime")
                val text = if (datetimeAttr.isNotBlank()) datetimeAttr else element.text().trim()
                
                val parsed = parseDateTimeText(text)
                if (parsed != null) return parsed
            }
        }
        
        // Fallback: today's date, no time
        return LocalDate.now() to null
    }
    
    internal fun parseDateTimeText(text: String): Pair<LocalDate, LocalTime?>? {
        val cleanText = text.trim()
        
        // Try to extract time first (HH:mm pattern)
        val timeRegex = "\\b(\\d{1,2}):(\\d{2})\\b".toRegex()
        val timeMatch = timeRegex.find(cleanText)
        var time: LocalTime? = null
        if (timeMatch != null) {
            try {
                time = LocalTime.of(timeMatch.groupValues[1].toInt(), timeMatch.groupValues[2].toInt())
            } catch (e: Exception) {
                // ignore
            }
        }
        
        // Try date patterns
        for (formatter in DATE_FORMATTERS) {
            try {
                val date = LocalDate.parse(cleanText, formatter)
                // Assume current year, adjust if date already passed
                val now = LocalDate.now()
                val adjustedDate = if (date.isBefore(now.minusMonths(1))) date.plusYears(1) else date
                return adjustedDate to time
            } catch (e: DateTimeParseException) {
                // try next formatter
            }
        }
        
        // Try to find date in format "19/11" or "19 Nov"
        val dateRegex = "\\b(\\d{1,2})[/-](\\d{1,2})\\b".toRegex()
        val dateMatch = dateRegex.find(cleanText)
        if (dateMatch != null) {
            try {
                val day = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt()
                val date = LocalDate.of(LocalDate.now().year, month, day)
                val adjustedDate = if (date.isBefore(LocalDate.now().minusMonths(1))) date.plusYears(1) else date
                return adjustedDate to time
            } catch (e: Exception) {
                // ignore
            }
        }
        
        return null
    }
    
    internal fun extractTvChannel(card: Element): String? {
        // Look for TV badges/logos
        val tvSelectors = listOf(
            ".tv-badge img",
            ".tv-channel img",
            ".broadcaster img",
            "[class*='tv'] img",
            "[class*='channel'] img",
            ".tv-logo"
        )
        
        for (selector in tvSelectors) {
            val elements = card.select(selector)
            for (element in elements) {
                val alt = element.attr("alt").trim()
                val title = element.attr("title").trim()
                val src = element.attr("src").trim()
                
                val channel = alt.ifBlank { title }.ifBlank { 
                    // Try to infer from image filename
                    src.takeAfterLast("/").takeBeforeLast(".").replace("-", " ").replace("_", " ")
                }
                
                if (channel.isNotBlank() && channel.length > 2) {
                    return normalizeChannelName(channel)
                }
            }
        }
        
        // Fallback: look for text badges
        val textBadges = card.select(".badge, .tag, [class*='badge'], [class*='tag']")
        for (badge in textBadges) {
            val text = badge.text().trim()
            if (text.matches("(?i).*(ESPN|Disney|Star|TV|Premium|TyC|DirecTV|Cable|Flow).*")) {
                return normalizeChannelName(text)
            }
        }
        
        return null
    }
    
    internal fun extractMatchUrl(card: Element): String {
        val link = card.selectFirst("a[href]")
        return link?.attr("href")?.let { href ->
            if (href.startsWith("http")) href else "$BASE_URL$href"
        } ?: ""
    }
    
    internal fun cleanTeamName(name: String): String {
        return name
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\[.*?\\]"), "") // remove [1], [2] etc
            .trim()
    }
    
    internal fun normalizeChannelName(channel: String): String {
        val normalized = channel
            .uppercase()
            .replace(Regex("[^A-Z0-9+ ]"), "")
            .replace("PLUS", "+")
            .replace("PREMIUM", " PREMIUM")
            .trim()
        
        return when {
            normalized.contains("ESPN") -> "ESPN"
            normalized.contains("DISNEY") -> "Disney+"
            normalized.contains("STAR") -> "Star+"
            normalized.contains("TYC") -> "TyC Sports"
            normalized.contains("DIRECTV") -> "DirecTV Sports"
            normalized.contains("CABLE") -> "Cablevisión"
            normalized.contains("FLOW") -> "Flow Sports"
            normalized.contains("TV PUBLICA") -> "TV Pública"
            normalized.contains("DEPORTV") -> "DeporTV"
            else -> channel
        }
    }
    
    internal fun formatCompetitionName(key: String): String {
        return when (key) {
            "torneo-clausura" -> "Torneo Clausura 2026"
            "copa-libertadores" -> "Copa Libertadores"
            "la-liga" -> "LaLiga EA Sports"
            "formula-1" -> "Fórmula 1"
            else -> key.replace("-", " ").replace("_", " ").let { it.first().uppercase() + it.drop(1) }
        }
    }
    
    internal fun parseFallback(doc: Document, competitionKey: String): List<ParsedMatch> {
        // Last resort: find all links that look like match pages
        val matches = mutableListOf<ParsedMatch>()
        val links = doc.select("a[href*='/futbol/'], a[href*='/automovilismo/']")
        
        for (link in links.take(20)) { // limit to avoid noise
            val href = link.attr("href")
            val text = link.text().trim()
            
            if (text.length > 10 && text.contains(" vs ") || text.contains(" - ")) {
                val parts = text.split(" vs ", "-").map { it.trim() }
                if (parts.size >= 2) {
                    matches.add(ParsedMatch(
                        date = LocalDate.now(),
                        time = null,
                        homeTeam = cleanTeamName(parts[0]),
                        awayTeam = cleanTeamName(parts[1]),
                        competition = formatCompetitionName(competitionKey),
                        tvChannel = null,
                        url = if (href.startsWith("http")) href else "$BASE_URL$href"
                    ))
                }
            }
        }
        
        return matches
    }
    
    /**
     * Convert parsed matches to ScheduleEvent format
     */
    fun toScheduleEvents(matches: List<ParsedMatch>, referenceDate: LocalDate, referenceTime: LocalTime): List<ScheduleEvent> {
        val events = mutableListOf<ScheduleEvent>()
        val nowMinutes = referenceTime.hour * 60 + referenceTime.minute
        
        matches.forEachIndexed { idx, match ->
            val title = "${match.homeTeam} vs ${match.awayTeam}"
            val description = buildDescription(match)
            val category = match.competition
            
            val isToday = match.date == referenceDate
            val isTomorrow = match.date == referenceDate.plusDays(1)
            
            if (!isToday && !isTomorrow) return@forEachIndexed
            
            val minutesOfDay = match.time?.let { it.hour * 60 + it.minute } ?: 0
            val isLive = isToday && match.time != null && nowMinutes >= minutesOfDay && nowMinutes < minutesOfDay + 120
            val isUpcoming = isToday && match.time != null && minutesOfDay > nowMinutes && minutesOfDay - nowMinutes <= 120
            val isPast = isToday && match.time != null && nowMinutes >= minutesOfDay + 120
            
            val event = ScheduleEvent(
                id = "canchallena-${match.date}-${match.time?.format(TIME_FORMATTER) ?: "0000"}-$idx",
                date = match.date,
                time = match.time ?: LocalTime.of(20, 0),
                title = title,
                description = description,
                category = category,
                isLive = isLive,
                isUpcoming = isUpcoming,
                isPast = isPast,
                minutesOfDay = minutesOfDay
            )
            events.add(event)
        }
        
        return events
    }
    
    internal fun buildDescription(match: ParsedMatch): String {
        val parts = mutableListOf<String>()
        parts.add(match.competition)
        match.tvChannel?.let { parts.add(it) }
        if (match.url.isNotBlank()) parts.add("Ver en Canchallena")
        return parts.joinToString(" • ")
    }
}