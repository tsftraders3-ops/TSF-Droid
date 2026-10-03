package com.tsfdroid.ai.core.agent

/**
 * v1.3.0 round 21 (the 2026-10-03 field evidence): pure predicates that let
 * the harness tell a GAVE-UP price answer from a GROUNDED one, so the turn
 * gets one guided retry instead of shipping "I wasn't able to pull an actual
 * live XAU/USD number — the quote feed returned only page listings…" as the
 * final reply.
 *
 * Field shape of the failure (screenshot 20:11): the model ran web_search,
 * the results were page listings without a figure, and the model honestly
 * surrendered. The harness knows better than the model what it can still
 * do: fetch_url a promising result. These predicates decide when to say so.
 *
 * Pure Kotlin, no Android dependencies — unit-testable on the JVM
 * (same pattern as [SearchQueryQuality]).
 */
internal object AnswerQuality {

    /** Query words that make an ask a NUMBER-seeking question. */
    private val PRICE_QUERY_HINTS = listOf(
        "price", "rate", "quote", "quotes", "cost", "worth", "how much",
        "value of", "stock", "share", "shares", "gold", "silver", "copper",
        "platinum", "bitcoin", "btc", "ethereum", "crypto", "usd", "inr",
        "eur", "gbp", "exchange rate", "xau", "xauusd", "sensex", "nifty",
        "nasdaq", "nvda", "oil", "crude", "petrol", "diesel", "benchmark",
        "index", "market cap", "dividend", "pe ratio", "52-week", "52 week"
    )

    /**
     * A figure as a user needs to see it: a currency symbol or code next to
     * digits (either order), or digits with a per-unit quantifier. Matches
     * "$2,650", "₹15.50", "4209.3 USD", "USD 2,650", "$ 180–230",
     * "4,156/oz", "3,900 per ounce", "72.5 USD/bbl".
     */
    private val PRICE_FIGURE = Regex(
        """(?i)([$₹€£¥]\s?\d|\d[\d,.]*\s*(usd|inr|eur|gbp|dollars|rupees|/oz|per (?:ounce|share|gram|barrel|unit|litre|liter))|usd\s?[\d,.]{2,}|inr\s?[\d,.]{2,})"""
    )

    /**
     * Refusal-shaped endings for a number-seeking ask — the model stating it
     * could NOT obtain the figure. Checked over the whole reply (a give-up
     * may sit after a paragraph of context).
     */
    private val GAVE_UP_PHRASES = listOf(
        "wasn't able to pull", "was not able to pull", "couldn't pull",
        "could not pull", "can't pull", "cannot pull", "unable to pull",
        "couldn't extract", "could not extract", "no figure to quote",
        "don't have a live quote", "do not have a live quote",
        "no live quote feed", "couldn't get a verified",
        "could not get a verified", "can't give you a verified",
        "cannot give you a verified", "no verified current",
        "couldn't retrieve", "could not retrieve", "unable to retrieve",
        "couldn't find", "could not find", "no tool access",
        "tools aren't available", "tools are not available",
        "not available in this session", "couldn't confirm the exact",
        "i have no figure", "no actual", "returned only page listings",
        "rather than a price"
    )

    /** True when the user's ask is a number-seeking question. */
    fun isPriceLikeQuery(query: String): Boolean {
        val q = " ${query.lowercase().replace(Regex("[^a-z0-9%$/]+"), " ").trim()} "
        return PRICE_QUERY_HINTS.any { q.contains(it) }
    }

    /** True when [text] shows the user an actual number for the ask. */
    fun containsPriceFigure(text: String): Boolean =
        PRICE_FIGURE.containsMatchIn(text)

    /**
     * True when a reply to a number-seeking ask reads as giving up. Used by
     * the harness ONLY after tools already ran — the reply must contain a
     * give-up phrase AND no figure (a reply like "goldprice.org was down, but
     * Kitco shows $2,109/oz" has a figure and is a fine answer).
     */
    fun isGiveUpAnswer(reply: String): Boolean {
        val r = reply.lowercase()
        if (containsPriceFigure(reply)) return false
        return GAVE_UP_PHRASES.any { r.contains(it) }
    }
}
