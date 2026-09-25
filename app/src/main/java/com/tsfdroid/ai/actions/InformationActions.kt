package com.tsfdroid.ai.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
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

        /**
         * In-app HTTP GET used by the real web capability (v1.0.5): WEB_SEARCH,
         * GET_NEWS, SUMMARIZE_URL and FETCH_URL fetch live data over the
         * network WITHOUT opening a browser. Returns null on any failure —
         * callers degrade to the browser-intent fallback so the action never
         * regresses when the endpoint is unreachable.
         */
        fun httpGetText(url: String, maxBytes: Int = MAX_FETCH_BYTES): String? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = FETCH_TIMEOUT_MS
                connection.readTimeout = FETCH_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
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

        private fun openInBrowser(context: Context, url: String): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened the browser for you.", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Couldn't reach the internet right now.")
            }
        }
    }

    /**
     * v1.0.5: REAL in-app web search — fetches DuckDuckGo Lite (the JS-free
     * HTML endpoint) and returns the top results as text the agent can reason
     * over, reference in later steps, or read aloud. No browser opens; the
     * browser-intent flow remains only as the offline fallback.
     */
    private class WebSearchAction : Action {
        override val name: String = "WEB_SEARCH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: return ActionResult(false, null, "query parameter is missing")
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val html = httpGetText("https://lite.duckduckgo.com/lite/?q=$encQuery")
            val results = html?.let { WebContentParsers.parseDuckDuckGoLite(it) }.orEmpty()
            val listing = results.mapIndexedNotNull { index, r ->
                if (r.title.isBlank()) return@mapIndexedNotNull null
                val snippet = r.snippet.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                "${index + 1}. ${r.title}$snippet\n   ${r.url}"
            }.joinToString("\n")
            if (listing.isBlank()) {
                Log.w(TAG, "In-app search returned no results for: $query — browser fallback")
                return openInBrowser(context, "https://www.google.com/search?q=$encQuery")
            }
            return ActionResult(true, "Top web results for '$query':\n$listing", null)
        }
    }

    private class GetWeatherAction : Action {
        override val name: String = "GET_WEATHER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            var location = params["location"]

            // If no location provided or it's a generic placeholder, try to resolve from device
            if (location == null || location == "current location" || location.isBlank()) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    val hasLocationPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasLocationPermission && locationManager != null) {
                        val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                            ?: locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)

                        if (lastLocation != null) {
                            // Reverse geocode to city name
                            try {
                                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(lastLocation.latitude, lastLocation.longitude, 1)
                                location = addresses?.firstOrNull()?.locality
                                    ?: addresses?.firstOrNull()?.subAdminArea
                                    ?: addresses?.firstOrNull()?.adminArea
                            } catch (e: Exception) {
                                // Geocoder failed, use coordinates
                                location = "${lastLocation.latitude},${lastLocation.longitude}"
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w("GetWeather", "Location permission denied: ${e.message}")
                } catch (e: Exception) {
                    Log.w("GetWeather", "Location resolution failed: ${e.message}")
                }
            }

            // Final fallback
            if (location == null || location.isBlank()) {
                location = "my location"
            }

            return try {
                val weatherCondition = try {
                    val url = java.net.URL("https://wttr.in/${URLEncoder.encode(location, "UTF-8")}?format=%C,+%t")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.inputStream.bufferedReader().use { it.readText().trim() }
                } catch (e: Exception) {
                    Log.e("GetWeather", "Failed to fetch weather from wttr.in: ${e.message}")
                    "Unknown"
                }

                val query = URLEncoder.encode("weather in $location", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val resultMsg = if (weatherCondition != "Unknown") {
                    "The current weather in $location is $weatherCondition. Opened search for details."
                } else {
                    "Here's the weather for $location! Opened search for details."
                }
                ActionResult(true, resultMsg, null)
            } catch (e: Exception) {
                Log.e("GetWeather", "Weather failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check the weather right now. Please check your internet connection.")
            }
        }
    }

    /**
     * v1.0.5: REAL in-app news — fetches Google News RSS for the topic and
     * returns the top headlines as text (data-producing step, no browser).
     * Browser flow only as the offline fallback.
     */
    private class GetNewsAction : Action {
        override val name: String = "GET_NEWS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"] ?: "latest news"
            val encQuery = URLEncoder.encode("news $topic", "UTF-8")
            val xml = httpGetText("https://news.google.com/rss/search?q=$encQuery")
            val headlines = xml?.let { WebContentParsers.parseRssHeadlines(it, limit = 5) }.orEmpty()
            if (headlines.isEmpty()) {
                Log.w(TAG, "In-app news returned no headlines for: $topic — browser fallback")
                return openInBrowser(context, "https://news.google.com/search?q=$encQuery")
            }
            val listing = headlines.mapIndexed { index, title -> "${index + 1}. $title" }
                .joinToString("\n")
            return ActionResult(true, "Latest news on '$topic':\n$listing", null)
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
                // Fallback: Web search calculation
                val encExpr = URLEncoder.encode(expression, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$encExpr".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Hmm, I couldn't calculate that directly. Let me Google it for you.", e.localizedMessage, true)
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

    private class TranslateAction : Action {
        override val name: String = "TRANSLATE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val text = params["text"] ?: return ActionResult(false, null, "text is missing")
            val from = params["from"] ?: "auto"
            val to = params["to"] ?: "en"
            return try {
                val encText = URLEncoder.encode(text, "UTF-8")
                val uri = "https://translate.google.com/?sl=$from&tl=$to&text=$encText&op=translate".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opening Google Translate for you!", null)
            } catch (e: Exception) {
                Log.e("Translate", "Translation failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the translator right now.")
            }
        }
    }

    private class DefineWordAction : Action {
        override val name: String = "DEFINE_WORD"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val word = params["word"] ?: return ActionResult(false, null, "word parameter is missing")
            return try {
                val query = URLEncoder.encode("define $word", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the definition of '$word'!", null)
            } catch (e: Exception) {
                Log.e("DefineWord", "Definition failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look that up right now.")
            }
        }
    }

    private class ConvertUnitsAction : Action {
        override val name: String = "CONVERT_UNITS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val value = params["value"] ?: return ActionResult(false, null, "value is missing")
            val from = params["from"] ?: ""
            val to = params["to"] ?: ""
            return try {
                val query = URLEncoder.encode("convert $value $from to $to", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Converting $value $from to $to for you!", null)
            } catch (e: Exception) {
                Log.e("ConvertUnits", "Conversion failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't convert those units right now.")
            }
        }
    }

    private class CurrencyConvertAction : Action {
        override val name: String = "CURRENCY_CONVERT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val amount = params["amount"] ?: return ActionResult(false, null, "amount is missing")
            val from = params["from"] ?: ""
            val to = params["to"] ?: ""
            return try {
                val query = URLEncoder.encode("convert $amount $from to $to", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Converting $amount $from to $to for you!", null)
            } catch (e: Exception) {
                Log.e("CurrencyConvert", "Currency failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't convert the currency right now.")
            }
        }
    }

    private class CheckStockAction : Action {
        override val name: String = "CHECK_STOCK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val symbol = params["symbol"] ?: return ActionResult(false, null, "symbol is missing")
            return try {
                val query = URLEncoder.encode("stock $symbol", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the stock info for $symbol!", null)
            } catch (e: Exception) {
                Log.e("CheckStock", "Stock check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look up that stock right now.")
            }
        }
    }

    /**
     * v1.0.5: fetches the page in-app and returns its readable text so the
     * planner (or a later CHAT step consuming this output via dependsOn) does
     * the actual summarizing. Browser flow only as the offline fallback.
     */
    private class SummarizeUrlAction : Action {
        override val name: String = "SUMMARIZE_URL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val url = params["url"] ?: return ActionResult(false, null, "url is missing")
            val normalized = if (url.startsWith("http")) url else "https://$url"
            val pageText = httpGetText(normalized)
                ?.let { WebContentParsers.htmlToText(it, maxChars = 6_000) }
            if (pageText.isNullOrBlank()) {
                return openInBrowser(context, normalized)
            }
            return ActionResult(true, "Page content of $normalized:\n$pageText", null)
        }
    }

    /**
     * v1.0.5 NEW: fetches a URL's page text in-app — real internet data
     * without opening Chrome. The primary building block for "fetch X from
     * the internet" style tasks; downstream steps reference this output via
     * dependsOn.
     */
    private class FetchUrlAction : Action {
        override val name: String = "FETCH_URL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val url = params["url"] ?: return ActionResult(false, null, "url parameter is missing")
            val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            val pageText = httpGetText(normalized)
                ?.let { WebContentParsers.htmlToText(it, maxChars = 8_000) }
            if (pageText.isNullOrBlank()) {
                return ActionResult(false, null, "Couldn't fetch that page. Check the URL or your internet.")
            }
            return ActionResult(true, "Content of $normalized:\n$pageText", null)
        }
    }

    private class FactCheckAction : Action {
        override val name: String = "FACT_CHECK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val claim = params["claim"] ?: return ActionResult(false, null, "claim is missing")
            return try {
                val query = URLEncoder.encode("fact check $claim", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Let me check that for you!", null)
            } catch (e: Exception) {
                Log.e("FactCheck", "Fact check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check that right now.")
            }
        }
    }
}
