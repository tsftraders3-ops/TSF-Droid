package com.tsfdroid.ai.actions

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.tsfdroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MediaActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `verified playback is reported as success`() = runBlocking {
        val verifier = RecordingVerifier(result = true)
        registerSpotifyHandler()

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "spotify"))

        assertTrue(result.success)
        assertEquals("spotify", verifier.app)
        assertEquals("Daft Punk", verifier.query)
    }

    @Test
    fun `launch without verified playback stays a success with a tap hint`() = runBlocking {
        val verifier = RecordingVerifier(result = false)
        registerSpotifyHandler()

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "spotify"))

        // v1.0.4: the app opened with the requested search — unverified
        // autoplay is a soft outcome, not a step failure.
        assertTrue(result.success)
        assertTrue(result.data!!.contains("spotify"))
        assertTrue(result.data!!.contains("Daft Punk"))
    }

    @Test
    fun `unsupported app fails before launching or verifying`() = runBlocking {
        val verifier = RecordingVerifier(result = true)

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "unknown"))

        assertFalse(result.success)
        assertEquals("That music app is not supported.", result.error)
        assertFalse(verifier.called)
    }

    @Test
    fun `youtube search page with unverified playback stays a success with tap hint`() = runBlocking {
        val verifier = RecordingVerifier(result = false)
        registerYoutubeHandler("kiya baat hai")

        val result = playYoutube(verifier, "kiya baat hai")

        // v1.0.4: we open YouTube's SEARCH RESULTS page — playback starts
        // only after the user taps a video, so strict verification here
        // always failed the step (the v1.0.3 FAILED log the user saw).
        assertTrue(result.success)
        assertTrue(result.data!!.contains("kiya baat hai"))
        assertTrue(result.data!!.contains("Tap a video"))
    }

    @Test
    fun `youtube verified playback still reports playing`() = runBlocking {
        val verifier = RecordingVerifier(result = true)
        registerYoutubeHandler("lofi beats")

        val result = playYoutube(verifier, "lofi beats")

        assertTrue(result.success)
        assertTrue(result.data!!.contains("Playing 'lofi beats'"))
    }

    @Test
    fun `query matching uses media title artist and album`() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Get Lucky")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Daft Punk")
            .build()

        assertTrue(AndroidMediaPlaybackVerifier.matchesQuery(metadata, "Daft Punk Get Lucky"))
        assertFalse(AndroidMediaPlaybackVerifier.matchesQuery(metadata, "Random Access Memories"))
    }

    @Test
    fun `youtube music package is accepted as a youtube target`() {
        assertTrue(AndroidMediaPlaybackVerifier.isTargetApp("com.google.android.apps.youtube.music", "youtube"))
        assertFalse(AndroidMediaPlaybackVerifier.isTargetApp("com.android.browser", "youtube"))
    }

    private suspend fun playMusic(
        verifier: RecordingVerifier,
        params: Map<String, String>
    ): ActionResult = MediaActions(verifier)
        .getActions()
        .first { it.name == "PLAY_MUSIC" }
        .execute(params, context)

    private suspend fun playYoutube(
        verifier: RecordingVerifier,
        query: String
    ): ActionResult = MediaActions(verifier)
        .getActions()
        .first { it.name == "PLAY_YOUTUBE" }
        .execute(mapOf("query" to query), context)

    @Suppress("DEPRECATION")
    private fun registerYoutubeHandler(query: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.google.android.youtube"
                name = "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"
            }
        }
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        ).apply { setPackage("com.google.android.youtube") }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    @Suppress("DEPRECATION")
    private fun registerSpotifyHandler() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.spotify.music"
                name = "PlayerActivity"
            }
        }
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("spotify:search:Daft+Punk")
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    private class RecordingVerifier(private val result: Boolean) : MediaPlaybackVerifier {
        var called = false
        var app: String? = null
        var query: String? = null

        override suspend fun awaitVerifiedPlayback(
            context: Context,
            app: String,
            query: String
        ): Boolean {
            called = true
            this.app = app
            this.query = query
            return result
        }
    }
}
