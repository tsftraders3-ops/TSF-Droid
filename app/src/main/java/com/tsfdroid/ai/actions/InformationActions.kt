package com.tsfdroid.ai.actions

import android.content.Context
import android.util.Log
import com.tsfdroid.ai.actions.base.Action
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.web.WebContentParsers
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InformationActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        WebSearchAction(),
        GetWeatherAction(),
        GetNewsAction(),
        CalculateAction(),
        TranslateAction(),
        DefineWordAction(),
        ConvertUnitsAction(),
        CurrencyConvertAction(),
        CheckStockAction(),
        SummarizeUrlAction(),
        FetchUrlAction(),
        FactCheckAction()
    )

    companion object {
        private const val TAG = "InformationActions"
        private const val FETCH_TIMEOUT_MS = 12_000
        private const val MAX_FETCH_BYTES = 512_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val USER_AGENT_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * In-app HTTP GET used by the real web capability: WEB_SEARCH,
         * GET_NEWS, SUMMARIZE_URL, FETCH_URL and the rest of the information
         * actions fetch live data over the network WITHOUT opening a browser.
         * Returns null on any failure — callers walk their own backend chain.
         */
        fun httpGetText(
            url: String,
            maxBytes: Int = MAX_FETCH_BYTES,
            userAgent: String = USER_AGENT,
            timeoutMs: Int = FETCH_TIMEOUT_MS
        ): String? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "HTTP fetch non-2xx: $url -> ${connection.responseCode}")
                    connection.disconnect()
                    return null
                }
                val stream = connection.inputStream
                val buffer = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = stream.read(buffer, read, maxBytes - read)
                    if (n < 0) break
                    read += n
                }
                stream.close()
                connection.disconnect()
                String(buffer, 0, read, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "HTTP fetch failed: $url -> ${e.localizedMessage}")
                null
            }
        }

        /**
         * v1.0.6: resilient page fetch — mobile UA, then desktop UA (some
         * sites 403 the mobile profile), then the r.jina.ai reader proxy
         * (returns clean text for pages that block plain HTTP clients).
         * Every data action walks this before admitting failure; none of
         * them open a browser.
         */
        fun fetchPageText(url: String, maxChars: Int): String? {
            val direct = httpGetText(url)?.let { WebContentParsers.htmlToText(it, maxChars) }
            if (!direct.isNullOrBlank()) return direct
            val desktop = httpGetText(url, userAgent = USER_AGENT_DESKTOP)
                ?.let { WebContentParsers.htmlToText(it, maxChars) }
            if (!desktop.isNullOrBlank()) return desktop
            val viaReader = httpGetText("https://r.jina.ai/$url", maxBytes = 400_000, timeoutMs = 20_000)
            if (!viaReader.isNullOrBlank()) {
                val text = viaReader.take(maxChars)
                if (text.isNotBlank()) return text
            }
            return null
        }

        /**
         * v1.0.6: multi-backend in-app search. DDG Lite now serves a 202
         * bot-challenge to many clients (zero parsed results → the v1.0.5
         * field failure "Opened the browser for you."), so the chain walks
         * four REAL backends and only then reports failure honestly. The
         * browser is NEVER opened for data — an opened browser contributes
         * nothing to downstream plan steps and the user explicitly asked
         * for no-browser operation.
         */
        fun searchWeb(query: String): String? {
            val encQuery = URLEncoder.encode(query, "UTF-8")

            val lite = httpGetText("https://lite.duckduckgo.com/lite/?q=$encQuery")
                ?.let { WebContentParsers.parseDuckDuckGoLite(it) }.orEmpty()
            if (lite.isNotEmpty()) return renderResults(query, lite)

            val html = httpGetText("https://html.duckduckgo.com/html/?q=$encQuery")
                ?.let { WebContentParsers.parseDuckDuckGoHtml(it) }.orEmpty()
            if (html.isNotEmpty()) return renderResults(query, html)

            val bing = httpGetText("https://www.bing.com/search?q=$encQuery", userAgent = USER_AGENT_DESKTOP)
                ?.let { WebContentParsers.parseBingResults(it) }.orEmpty()
            if (bing.isNotEmpty()) return renderResults(query, bing)

            // Last real backend: Google News RSS answers almost any query
            // with live headlines — real data, no browser.
            val news = httpGetText("https://news.google.com/rss/search?q=$encQuery")
                ?.let { WebContentParsers.parseRssHeadlines(it, limit = 5) }.orEmpty()
            if (news.isNotEmpty()) {
                return "Top web results for '$query':\n" + news.mapIndexed { i, t -> "${i + 1}. $t" }
                    .joinToString("\n")
            }
            return null
        }

        private fun renderResults(query: String, results: List<WebContentParsers.WebSearchResult>): String {
            val listing = results.mapIndexedNotNull { index, r ->
                if (r.title.isBlank()) return@mapIndexedNotNull null
                val snippet = r.snippet.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                "${index + 1}. ${r.title}$snippet\n   ${r.url}"
            }.joinToString("\n")
            return "Top web results for '$query':\n$listing"
        }

        /**
         * Shared currency conversion (open.er-api.com primary, frankfurter
         * fallback) used by CURRENCY_CONVERT and unit-conversion currency
         * codes. Companion-scoped so nested action classes can call it.
         */
        fun currencyConvert(amount: Double, from: String, to: String): ActionResult {
            try {
                val body = httpGetText("https://open.er-api.com/v6/latest/${from.uppercase()}", timeoutMs = 8_000)
                val rate = body?.let { b ->
                    Regex("\"$to\"\\s*:\\s*([0-9.]+)").find(b)?.groupValues?.get(1)?.toDoubleOrNull()
                }
                if (rate != null) {
                    val converted = amount * rate
                    return ActionResult(
                        true,
                        String.format(java.util.Locale.US, "%.2f %s = %.2f %s (rate %.4f)", amount, from.uppercase(), converted, to.uppercase(), rate),
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w("CurrencyConvert", "er-api failed: ${e.localizedMessage}")
            }
            try {
                val body = httpGetText("https://api.frankfurter.app/latest?from=${from.uppercase()}&to=${to.uppercase()}", timeoutMs = 8_000)
                val rate = body?.let { b ->
                    Regex("\"$to\"\\s*:\\s*([0-9.]+)").find(b)?.groupValues?.get(1)?.toDoubleOrNull()
                }
                if (rate != null) {
                    val converted = amount * rate
                    return ActionResult(
                        true,
                        String.format(java.util.Locale.US, "%.2f %s = %.2f %s (rate %.4f)", amount, from.uppercase(), converted, to.uppercase(), rate),
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w("CurrencyConvert", "frankfurter failed: ${e.localizedMessage}")
            }
            return ActionResult(false, null, "Couldn't fetch exchange rates right now.")
        }
    }

    /**
     * REAL in-app web search — walks the multi-backend chain (DDG Lite →
     * DDG HTML → Bing → Google News RSS) and returns the top results as
     * text the agent can reason over, reference in later steps, or read
     * aloud. No browser opens, ever.
     */
    private class WebSearchAction : Action {
        override val name: String = "WEB_SEARCH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // v1.0.6: tolerate the planner putting the query in another slot
            // (the field failure "Fallback failed: Needs user input: I need
            // the query" came from a WEB_SEARCH fallback whose params only
            // carried `url`).
            val query = params["query"]?.takeIf { it.isNotBlank() }
                ?: params["url"]?.takeIf { it.isNotBlank() }
                ?: params["topic"]?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "query parameter is missing")
            val results = searchWeb(query)
                ?: return ActionResult(false, null, "No search results came back for '$query'. The backend chain (DDG Lite, DDG HTML, Bing, News RSS) is unreachable from this network.")
            return ActionResult(true, results, null)
        }
    }

    /**
     * REAL weather data in-app: Open-Meteo geocoding + forecast (no API key)
     * with wttr.in as the text fallback. Returns the actual conditions —
     * no browser, no "opened search for details".
     */
    private class GetWeatherAction : Action {
        override val name: String = "GET_WEATHER"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            var location = params["location"]?.takeIf { it.isNotBlank() }

            if (location == null || location.equals("current location", true) || location.equals("my location", true)) {
                location = resolveDeviceLocation(context) ?: "my location"
            }

            // 1) Open-Meteo: geocode the place, then fetch current weather.
            try {
                val encLoc = URLEncoder.encode(location, "UTF-8")
                val geoJson = httpGetText("https://geocoding-api.open-meteo.com/v1/search?name=$encLoc&count=1", timeoutMs = 8_000)
                val geo = geoJson?.let { json ->
                    Regex("\"latitude\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1) to
                        Regex("\"longitude\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)
                }
                if (geo?.first != null && geo.second != null) {
                    val weatherJson = httpGetText(
                        "https://api.open-meteo.com/v1/forecast?latitude=${geo.first}&longitude=${geo.second}&current_weather=true",
                        timeoutMs = 8_000
                    )
                    val temp = weatherJson?.let { Regex("\"temperature\"\\s*:\\s*(-?[0-9.]+)").find(it)?.groupValues?.get(1) }
                    val wind = weatherJson?.let { Regex("\"windspeed\"\\s*:\\s*(-?[0-9.]+)").find(it)?.groupValues?.get(1) }
                    if (temp != null) {
                        return ActionResult(
                            true,
                            "Current weather in $location: ${temp}°C" + (wind?.let { ", wind ${it} km/h" } ?: ""),
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("GetWeather", "Open-Meteo failed: ${e.localizedMessage}")
            }

            // 2) wttr.in one-line text format.
            try {
                val encLoc = URLEncoder.encode(location, "UTF-8")
                val oneLine = httpGetText("https://wttr.in/$encLoc?format=%C,+%t,+wind+%w", timeoutMs = 8_000)?.trim()
                if (!oneLine.isNullOrBlank() && !oneLine.startsWith("<")) {
                    return ActionResult(true, "Current weather in $location: $oneLine", null)
                }
            } catch (e: Exception) {
                Log.w("GetWeather", "wttr.in failed: ${e.localizedMessage}")
            }

            return ActionResult(false, null, "Couldn't fetch the weather right now (weather services unreachable).")
        }

        private fun resolveDeviceLocation(context: Context): String? {
            return try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val hasPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasPermission && locationManager != null) {
                    val last = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        ?: locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    if (last != null) {
                        try {
                            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(last.latitude, last.longitude, 1)
                            addresses?.firstOrNull()?.locality
                                ?: addresses?.firstOrNull()?.subAdminArea
                                ?: addresses?.firstOrNull()?.adminArea
                        } catch (e: Exception) {
                            "${last.latitude},${last.longitude}"
                        }
                    } else null
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * REAL in-app news — Google News RSS with a Bing News RSS fallback,
     * returning actual headlines as data for the agent and later steps.
     * No browser.
     */
    private class GetNewsAction : Action {
        override val name: String = "GET_NEWS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"]?.takeIf { it.isNotBlank() } ?: "latest news"
            val encQuery = URLEncoder.encode("news $topic", "UTF-8")
            val headlines = httpGetText("https://news.google.com/rss/search?q=$encQuery")
                ?.let { WebContentParsers.parseRssHeadlines(it, limit = 5) }.orEmpty()
            if (headlines.isNotEmpty()) {
                val listing = headlines.mapIndexed { index, title -> "${index + 1}. $title" }.joinToString("\n")
                return ActionResult(true, "Latest news on '$topic':\n$listing", null)
            }
            val bingNews = httpGetText("https://www.bing.com/news/search?q=$encQuery&format=RSS", userAgent = USER_AGENT_DESKTOP)
                ?.let { WebContentParsers.parseRssHeadlines(it, limit = 5) }.orEmpty()
            if (bingNews.isNotEmpty()) {
                val listing = bingNews.mapIndexed { index, title -> "${index + 1}. $title" }.joinToString("\n")
                return ActionResult(true, "Latest news on '$topic':\n$listing", null)
            }
            return ActionResult(false, null, "Couldn't fetch news right now (news services unreachable).")
        }
    }

    private class CalculateAction : Action {
        override val name: String = "CALCULATE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val expression = params["expression"] ?: return ActionResult(false, null, "expression parameter is missing")
            return try {
                val sanitized = expression.replace(" ", "")
                val result = evaluateSimpleExpression(sanitized)
                ActionResult(true, "$expression = $result", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Couldn't calculate '$expression' — supported: a+b, a-b, a*b, a/b.")
            }
        }

        private fun evaluateSimpleExpression(expr: String): Double {
            // Very basic evaluator for +, -, *, /
            return when {
                expr.contains("+") -> {
                    val parts = expr.split("+")
                    parts[0].toDouble() + parts[1].toDouble()
                }
                expr.contains("-") -> {
                    val parts = expr.split("-")
                    parts[0].toDouble() - parts[1].toDouble()
                }
                expr.contains("*") -> {
                    val parts = expr.split("*")
                    parts[0].toDouble() * parts[1].toDouble()
                }
                expr.contains("/") -> {
                    val parts = expr.split("/")
                    parts[0].toDouble() / parts[1].toDouble()
                }
                else -> expr.toDouble()
            }
        }
    }

    /**
     * v1.0.6: REAL translation in-app via the public gtx endpoint with
     * MyMemory as fallback. No browser.
     */
    private class TranslateAction : Action {
        override val name: String = "TRANSLATE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val text = params["text"] ?: return ActionResult(false, null, "text is missing")
            val from = params["from"]?.takeIf { it.isNotBlank() } ?: "auto"
            val to = params["to"]?.takeIf { it.isNotBlank() } ?: "en"
            try {
                val encText = URLEncoder.encode(text, "UTF-8")
                val body = httpGetText(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$encText",
                    timeoutMs = 8_000
                )
                val translated = body?.let { b ->
                    Regex("\\[\\[\\[\"(.*?)\"").find(b)?.groupValues?.get(1)
                }
                if (!translated.isNullOrBlank()) {
                    return ActionResult(true, "Translation ($from → $to): $translated", null)
                }
            } catch (e: Exception) {
                Log.w("Translate", "gtx failed: ${e.localizedMessage}")
            }
            try {
                val encText = URLEncoder.encode(text, "UTF-8")
                val body = httpGetText(
                    "https://api.mymemory.translated.net/get?q=$encText&langpair=$from|$to",
                    timeoutMs = 8_000
                )
                val translated = body?.let { b ->
                    Regex("\"translatedText\"\\s*:\\s*\"(.*?)\"").find(b)?.groupValues?.get(1)
                }
                if (!translated.isNullOrBlank()) {
                    return ActionResult(true, "Translation ($from → $to): ${WebContentParsers.decodeEntities(translated)}", null)
                }
            } catch (e: Exception) {
                Log.w("Translate", "MyMemory failed: ${e.localizedMessage}")
            }
            return ActionResult(false, null, "Couldn't translate right now (translation services unreachable).")
        }
    }

    /**
     * v1.0.6: REAL dictionary lookup via dictionaryapi.dev with a WEB_SEARCH
     * fallback. No browser.
     */
    private class DefineWordAction : Action {
        override val name: String = "DEFINE_WORD"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val word = params["word"] ?: return ActionResult(false, null, "word parameter is missing")
            try {
                val body = httpGetText(
                    "https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word, "UTF-8")}",
                    timeoutMs = 8_000
                )
                val definition = body?.let { b ->
                    Regex("\"definition\"\\s*:\\s*\"(.*?)\"").find(b)?.groupValues?.get(1)
                }
                if (!definition.isNullOrBlank()) {
                    return ActionResult(true, "Definition of '$word': ${WebContentParsers.decodeEntities(definition)}", null)
                }
            } catch (e: Exception) {
                Log.w("DefineWord", "dictionaryapi failed: ${e.localizedMessage}")
            }
            val search = searchWeb("define $word")
            if (search != null) return ActionResult(true, search, null)
            return ActionResult(false, null, "Couldn't look up '$word' right now.")
        }
    }

    /**
     * v1.0.6: REAL unit conversion — a local table for the common physical
     * units (length/mass/temperature/volume/data), delegating currency codes
     * to the exchange-rate service. No browser.
     */
    private class ConvertUnitsAction : Action {
        override val name: String = "CONVERT_UNITS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val value = params["value"] ?: return ActionResult(false, null, "value is missing")
            val from = params["from"]?.lowercase()?.trim() ?: return ActionResult(false, null, "from is missing")
            val to = params["to"]?.lowercase()?.trim() ?: return ActionResult(false, null, "to is missing")
            val amount = value.toDoubleOrNull()

            // Currency codes delegate to the exchange-rate backend.
            val currencyCodes = setOf("usd", "eur", "gbp", "inr", "jpy", "aud", "cad", "chf", "cny", "sgd", "aed")
            if ((from in currencyCodes) && (to in currencyCodes) && amount != null) {
                return currencyConvert(amount, from, to)
            }

            if (amount != null) {
                val factor = conversionFactor(from, to)
                if (factor != null) {
                    return ActionResult(true, "$value $from = ${formatNumber(amount * factor)} $to", null)
                }
                // Temperature special cases
                val temp = convertTemperature(amount, from, to)
                if (temp != null) {
                    return ActionResult(true, "$value $from = ${formatNumber(temp)} $to", null)
                }
            }
            return ActionResult(false, null, "Couldn't convert $value $from to $to in-app (unsupported units).")
        }

        private fun formatNumber(n: Double): String =
            if (n == n.toLong().toDouble()) n.toLong().toString() else String.format(java.util.Locale.US, "%.4f", n).trimEnd('0').trimEnd('.')

        private fun convertTemperature(amount: Double, from: String, to: String): Double? {
            fun toCelsius(v: Double, unit: String): Double? = when (unit) {
                "c", "celsius" -> v
                "f", "fahrenheit" -> (v - 32) * 5 / 9
                "k", "kelvin" -> v - 273.15
                else -> null
            }
            fun fromCelsius(v: Double, unit: String): Double? = when (unit) {
                "c", "celsius" -> v
                "f", "fahrenheit" -> v * 9 / 5 + 32
                "k", "kelvin" -> v + 273.15
                else -> null
            }
            val c = toCelsius(amount, from) ?: return null
            return fromCelsius(c, to)
        }

        private fun conversionFactor(from: String, to: String): Double? {
            val length = mapOf(
                "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
                "inch" to 0.0254, "in" to 0.0254, "feet" to 0.3048, "ft" to 0.3048,
                "yard" to 0.9144, "mile" to 1609.344
            )
            val mass = mapOf(
                "mg" to 1e-6, "g" to 0.001, "kg" to 1.0, "ounce" to 0.0283495, "oz" to 0.0283495,
                "pound" to 0.453592, "lb" to 0.453592, "tonne" to 1000.0
            )
            val volume = mapOf(
                "ml" to 0.001, "l" to 1.0, "liter" to 1.0, "litre" to 1.0,
                "gallon" to 3.78541, "cup" to 0.24
            )
            val data = mapOf(
                "kb" to 1.0, "mb" to 1024.0, "gb" to 1_048_576.0, "tb" to 1_073_741_824.0
            )
            for (table in listOf(length, mass, volume, data)) {
                val f = table[from]
                val t = table[to]
                if (f != null && t != null) return f / t
            }
            return null
        }
    }

    /**
     * v1.0.6: REAL currency conversion via open.er-api.com (no key) with
     * frankfurter.app fallback. No browser.
     */
    private class CurrencyConvertAction : Action {
        override val name: String = "CURRENCY_CONVERT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ActionResult(false, null, "amount parameter must be a number")
            val from = params["from"]?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "from currency is missing")
            val to = params["to"]?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "to currency is missing")
            return currencyConvert(amount, from, to)
        }
    }

    /**
     * v1.0.6: REAL stock quote via Yahoo Finance's public chart endpoint.
     * No browser.
     */
    private class CheckStockAction : Action {
        override val name: String = "CHECK_STOCK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val symbol = params["symbol"]?.uppercase()?.trim()?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "symbol parameter is missing")
            try {
                val body = httpGetText(
                    "https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}?range=1d&interval=1d",
                    userAgent = USER_AGENT_DESKTOP,
                    timeoutMs = 8_000
                )
                val price = body?.let { b ->
                    Regex("\"regularMarketPrice\"\\s*:\\s*([0-9.]+)").find(b)?.groupValues?.get(1)
                }
                val currency = body?.let { b ->
                    Regex("\"currency\"\\s*:\\s*\"([A-Z]+)\"").find(b)?.groupValues?.get(1)
                }
                if (price != null) {
                    return ActionResult(true, "$symbol is at $price ${currency ?: ""} (latest session close).", null)
                }
            } catch (e: Exception) {
                Log.w("CheckStock", "Yahoo failed: ${e.localizedMessage}")
            }
            val search = searchWeb("$symbol stock price")
            if (search != null) return ActionResult(true, search, null)
            return ActionResult(false, null, "Couldn't fetch the stock quote right now.")
        }
    }

    /**
     * v1.0.6: fetches the page in-app (mobile UA → desktop UA → reader
     * proxy) and returns its readable text so the planner (or a later CHAT
     * step consuming this output via dependsOn) does the summarizing.
     * No browser.
     */
    private class SummarizeUrlAction : Action {
        override val name: String = "SUMMARIZE_URL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val url = params["url"] ?: return ActionResult(false, null, "url is missing")
            val normalized = if (url.startsWith("http")) url else "https://$url"
            val pageText = fetchPageText(normalized, maxChars = 6_000)
                ?: return ActionResult(false, null, "Couldn't fetch that page (tried direct, desktop profile and reader proxy). Check the URL or your internet.")
            return ActionResult(true, "Page content of $normalized:\n$pageText", null)
        }
    }

    /**
     * v1.0.6: fetches a URL's page text in-app with the full fallback chain
     * — real internet data without opening Chrome. The primary building
     * block for "fetch X from the internet" style tasks; downstream steps
     * reference this output via dependsOn.
     */
    private class FetchUrlAction : Action {
        override val name: String = "FETCH_URL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val url = params["url"]?.takeIf { it.isNotBlank() }
                ?: params["query"]?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "url parameter is missing")
            val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            val pageText = fetchPageText(normalized, maxChars = 8_000)
                ?: return ActionResult(false, null, "Couldn't fetch that page (tried direct, desktop profile and reader proxy). Check the URL or your internet.")
            return ActionResult(true, "Content of $normalized:\n$pageText", null)
        }
    }

    /**
     * v1.0.6: REAL fact checking — runs the multi-backend web search for the
     * claim and returns the evidence. No browser.
     */
    private class FactCheckAction : Action {
        override val name: String = "FACT_CHECK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val claim = params["claim"]?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "claim parameter is missing")
            val search = searchWeb("fact check $claim")
                ?: return ActionResult(false, null, "Couldn't check that right now (search services unreachable).")
            return ActionResult(true, search, null)
        }
    }
}
