package com.tsfdroid.ai.core.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.StreamChunk
import com.tsfdroid.ai.core.memory.EpisodicMemory
import com.tsfdroid.ai.core.memory.MemoryExtractor
import com.tsfdroid.ai.core.memory.MemoryManager
import com.tsfdroid.ai.core.memory.NotificationIntelligence
import com.tsfdroid.ai.core.memory.ProceduralMemory
import com.tsfdroid.ai.core.memory.SemanticMemory
import com.tsfdroid.ai.core.memory.WorkingMemory
import com.tsfdroid.ai.core.memory.graph.PersonalGrowthEngine
import com.tsfdroid.ai.core.routine.FakeMacroDao
import com.tsfdroid.ai.core.routine.FakeMemoryDao
import com.tsfdroid.ai.core.routine.FakeNotificationDao
import com.tsfdroid.ai.core.routine.FakeSensitiveMemoryStore
import com.tsfdroid.ai.core.routine.FakeTaskHistoryDao
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProfileStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.core.security.UserProfileStore
import com.tsfdroid.ai.data.db.OpenDroidDatabase
import com.tsfdroid.ai.data.db.entities.NotificationEntity
import com.tsfdroid.ai.data.repository.ConversationRepository
import com.tsfdroid.ai.data.repository.MemoryRepository
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch

/**
 * M-05 / M-06 remediation (audit fc9ea97):
 *
 * M-05 — the delayed auto-reply must honor revocation. The engine re-checked
 * ONLY `globalEnabled` after the 15-minute wait: a user who disabled the
 * WhatsApp switch, blacklisted the contact, or narrowed the whitelist during
 * the delay still had an automatic reply sent on their behalf. The
 * discriminator in these tests is whether the LLM was consulted (and the
 * reply dispatched) after the delay: config snapshots are served
 * per-collection, so the schedule-time read deterministically sees the
 * "enabled" world and the post-delay read sees the "revoked" world.
 *
 * M-06 — notification bodies, expected/received comparison texts, and sent
 * reply text must never reach Logcat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoReplyEngineRevocationTest {

    private lateinit var context: Context
    private lateinit var notificationDao: FakeNotificationDao
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var flipStore: FlipDataStore

    /** How many times the LLM was asked for a reply. */
    private val llmCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationDao = FakeNotificationDao()
        flipStore = FlipDataStore()
        settingsRepository = SettingsRepository(
            dataStore = flipStore,
            providerCredentialStore = FakeProviderCredentialStore(),
            runStartupMigration = false
        )
    }

    private fun buildEngine(
        sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ): AutoReplyEngine {
        val memoryRepository = MemoryRepository(
            memoryDao = FakeMemoryDao(),
            taskHistoryDao = FakeTaskHistoryDao(),
            macroDao = FakeMacroDao()
        )
        val notificationIntelligence = NotificationIntelligence(notificationDao, memoryRepository)
        val db = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val conversationRepository =
            ConversationRepository(db, db.conversationDao(), db.chatSessionDao())
        val memoryExtractor = MemoryExtractor(memoryRepository)
        val workingMemory = WorkingMemory(context, DeviceStateProvider(context))
        val personalGrowthEngine = PersonalGrowthEngine(
            memoryRepository = memoryRepository,
            sensitiveMemoryStore = FakeSensitiveMemoryStore(),
            workingMemory = workingMemory,
            notificationIntelligence = notificationIntelligence
        )
        val memoryManager = MemoryManager(
            workingMemory = workingMemory,
            episodicMemory = EpisodicMemory(conversationRepository),
            semanticMemory = SemanticMemory(memoryRepository, memoryExtractor),
            proceduralMemory = ProceduralMemory(memoryRepository),
            personalGrowthEngine = personalGrowthEngine,
            memoryExtractor = memoryExtractor,
            conversationRepository = conversationRepository,
            memoryRepository = memoryRepository,
            notificationIntelligence = notificationIntelligence,
            userProfileStore = FakeUserProfileStore(),
            context = context
        )
        val engine = AutoReplyEngine(
            notificationDao = notificationDao,
            llmProviderFactory = neverFactory(),
            memoryManager = memoryManager,
            notificationIntelligence = notificationIntelligence,
            replyDispatcher = ReplyDispatcher(context),
            settingsRepository = settingsRepository
        )
        // Test seams (production defaults preserved): intercept provider access
        // so the pipeline is hermetic (no network, no provider construction).
        engine.providerAccess = {
            llmCalls.incrementAndGet()
            CountingFakeProvider
        }
        engine.sleeper = sleeper
        return engine
    }

    @Suppress("UNCHECKED_CAST")
    private fun neverFactory(): LLMProviderFactory {
        // The providerAccess test seam intercepts every LLM access, so the
        // factory's own providers are never resolved. SAM lambdas throw if
        // that guarantee is ever broken — the test fails loudly instead of
        // silently hitting a network.
        fun <T : Any> unused(): javax.inject.Provider<T> =
            javax.inject.Provider { throw AssertionError("test provider must never be resolved") }
        fun <T : Any> unusedLazy(): dagger.Lazy<T> =
            dagger.Lazy { throw AssertionError("test lazy must never be resolved") }
        return LLMProviderFactory(
            claudeProvider = unused(),
            openAIProvider = unused(),
            geminiProvider = unused(),
            mistralProvider = unused(),
            groqProvider = unused(),
            ollamaProvider = unused(),
            openRouterProvider = unused(),
            togetherAIProvider = unused(),
            cohereProvider = unused(),
            deepSeekProvider = unused(),
            openCodeZenProvider = unused(),
            copilotProvider = unused(),
            customOpenAIProvider = unused(),
            gemmaProvider = unused(),
            liteRTLMProvider = unused(),
            hybridOnDeviceProvider = unused(),
            settingsRepository = settingsRepository,
            onDeviceLatencyTracker = com.tsfdroid.ai.core.llm.OnDeviceLatencyTracker(settingsRepository),
            actionDispatcher = unusedLazy(),
            intentClassifier = unusedLazy(),
            deviceStateProvider = DeviceStateProvider(context)
        )
    }

    private fun whatsappNotification(
        text: String = "can you send the Q3 numbers tonight"
    ) = NotificationEntity(
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        title = "Alice",
        text = text,
        timestamp = System.currentTimeMillis(),
        category = "MESSAGE",
        contactName = "Alice"
    )

    private fun prefs(
        global: Boolean = true,
        whatsapp: Boolean = true,
        delayMinutes: Int = 0,
        blacklist: Set<String> = emptySet(),
        whitelist: Set<String> = emptySet(),
        maxPerHour: Int = 3
    ): Preferences = mutablePreferencesOf(
        booleanPreferencesKey("auto_reply_global") to global,
        booleanPreferencesKey("auto_reply_whatsapp") to whatsapp,
        intPreferencesKey("auto_reply_delay_minutes") to delayMinutes,
        stringSetPreferencesKey("auto_reply_blacklist") to blacklist,
        stringSetPreferencesKey("auto_reply_whitelist") to whitelist,
        intPreferencesKey("auto_reply_max_per_hour") to maxPerHour
    )

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private fun settle(ms: Long = 400) = Thread.sleep(ms)

    // ── M-05: revocation during the delay ──────────────────────────────────

    @Test
    fun `positive control - an unrevoked reply reaches the LLM`() {
        flipStore.snapshots = listOf(prefs(), prefs())
        val engine = buildEngine()
        engine.scheduleAutoReply(whatsappNotification(), null, context)
        waitUntil { llmCalls.get() == 1 }
        settle()
        assertEquals(1, llmCalls.get())
    }

    @Test
    fun `per-app switch disabled during the delay blocks the reply`() {
        // First read: WhatsApp on. Post-delay read: WhatsApp off, global on.
        flipStore.snapshots = listOf(
            prefs(whatsapp = true),
            prefs(whatsapp = false)
        )
        val engine = buildEngine()
        engine.scheduleAutoReply(whatsappNotification(), null, context)
        // Give the job enough real time to complete the full (buggy or fixed)
        // pipeline, then assert the LLM was never consulted.
        settle(1500)
        assertEquals(
            "revoking the WhatsApp switch during the delay must stop the reply",
            0,
            llmCalls.get()
        )
    }

    @Test
    fun `contact blacklisted during the delay blocks the reply`() {
        flipStore.snapshots = listOf(
            prefs(blacklist = emptySet()),
            prefs(blacklist = setOf("Alice"))
        )
        val engine = buildEngine()
        engine.scheduleAutoReply(whatsappNotification(), null, context)
        settle(1500)
        assertEquals(
            "blacklisting the contact during the delay must stop the reply",
            0,
            llmCalls.get()
        )
    }

    @Test
    fun `whitelist narrowed during the delay blocks the reply`() {
        flipStore.snapshots = listOf(
            prefs(whitelist = setOf("Alice", "Bob")),
            prefs(whitelist = setOf("Bob"))
        )
        val engine = buildEngine()
        engine.scheduleAutoReply(whatsappNotification(), null, context)
        settle(1500)
        assertEquals(
            "removing the contact from the whitelist during the delay must stop the reply",
            0,
            llmCalls.get()
        )
    }

    @Test
    fun `global disable during the delay still blocks the reply`() {
        // Regression guard: this is the only revocation the old code handled.
        flipStore.snapshots = listOf(
            prefs(global = true),
            prefs(global = false)
        )
        val engine = buildEngine()
        engine.scheduleAutoReply(whatsappNotification(), null, context)
        settle(1500)
        assertEquals(0, llmCalls.get())
    }

    @Test
    fun `manual reply cancellation kills a pending reply before dispatch`() {
        // 15-minute configured delay; the test sleeper parks the job so the
        // cancel arrives deterministically mid-delay.
        val gate = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        flipStore.snapshots = listOf(prefs(delayMinutes = 15), prefs(delayMinutes = 15))
        val engine = buildEngine(sleeper = {
            entered.countDown()
            gate.await()
        })
        val notification = whatsappNotification()
        engine.scheduleAutoReply(notification, null, context)
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        engine.cancelPendingReply("com.whatsapp", "Alice")
        gate.complete(Unit)
        settle(800)
        assertEquals(
            "a manually cancelled pending reply must never reach the LLM",
            0,
            llmCalls.get()
        )
    }

    // ── M-06: no message content in Logcat ─────────────────────────────────

    @Test
    fun `sent reply text never reaches Logcat`() {
        ShadowLog.clear()
        flipStore.snapshots = listOf(prefs(), prefs())
        val engine = buildEngine()
        // Persist the notification the way the listener does before scheduling,
        // so markAsAutoReplied can resolve it.
        val stored = runBlocking {
            val id = notificationDao.insertNotification(whatsappNotification())
            whatsappNotification().copy(id = id)
        }
        val sbn = replyableWhatsAppNotification()
        engine.scheduleAutoReply(stored, sbn, context)

        // Positive control: the pipeline completed and marked the notification.
        waitUntil {
            runBlocking { notificationDao.getRecentNotifications(1) }
                .firstOrNull()?.isAutoReplied == true
        }
        settle()

        val replySecret = CountingFakeProvider.CANNED_REPLY
        val leaked = ShadowLog.getLogs().map { it.msg }.filter { msg ->
            msg.contains(replySecret.take(24))
        }
        assertEquals(
            "reply content must never be logged; leaking lines: $leaked",
            emptyList<String>(),
            leaked
        )
    }

    @Test
    fun `notification bodies never reach Logcat during the reply pipeline`() {
        ShadowLog.clear()
        flipStore.snapshots = listOf(prefs(), prefs())
        val engine = buildEngine()
        val secret = "can you send the Q3 numbers tonight"
        engine.scheduleAutoReply(whatsappNotification(text = secret), null, context)
        waitUntil { llmCalls.get() >= 1 }
        settle()
        val leaked = ShadowLog.getLogs().map { it.msg }.filter { it.contains(secret) }
        assertEquals(
            "notification bodies must never be logged; leaking lines: $leaked",
            emptyList<String>(),
            leaked
        )
    }

    private fun replyableWhatsAppNotification(): StatusBarNotification {
        val intent = Intent("com.tsfdroid.ai.TEST_REPLY")
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val remoteInput = RemoteInput.Builder("reply_key").setLabel("Reply").build()
        val icon = Icon.createWithResource(context, android.R.drawable.ic_menu_send)
        val action = Notification.Action.Builder(icon, "Reply", pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        val notification = Notification.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Alice")
            .setContentText("can you send the Q3 numbers tonight")
            .addAction(action)
            .build()
        return StatusBarNotification(
            "com.whatsapp", "com.tsfdroid.ai", 1, null, 0, 0, 0, notification,
            UserHandle.getUserHandleForUid(0), System.currentTimeMillis()
        )
    }
}

/**
 * Serves config snapshots in order, one per collection: collection #1 sees
 * snapshots[0] (the schedule-time world), collection #2+ sees snapshots[1+]
 * (the post-delay world). This makes the schedule-to-delay flip deterministic.
 */
class FlipDataStore : DataStore<Preferences> {
    var snapshots: List<Preferences> = listOf(emptyPreferences())
    private val collections = AtomicInteger(0)

    override val data: Flow<Preferences> = flow {
        val index = collections.getAndIncrement().coerceAtMost(snapshots.size - 1)
        emit(snapshots[index])
    }

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences
    ): Preferences {
        val current = snapshots.firstOrNull() ?: emptyPreferences()
        return transform(current)
    }
}

object CountingFakeProvider : LLMProvider {
    const val CANNED_REPLY = "SECRET_CANNED_REPLY_ThisIsNotForLogcat"

    override val name: String = "CountingFakeProvider"
    override val availableModels: List<String> = listOf("fake-model")

    override suspend fun complete(request: LLMRequest): LLMResponse = LLMResponse(
        content = CANNED_REPLY,
        tokensUsed = 10,
        model = "fake-model",
        provider = "CountingFakeProvider",
        latencyMs = 5
    )

    override fun streamComplete(request: LLMRequest): Flow<String> = flowOf(CANNED_REPLY)

    override suspend fun isAvailable(): Boolean = true
}

class FakeProviderCredentialStore : ProviderCredentialStore {
    override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
        MutableStateFlow(ProviderCredentialRecoveryState.Ready)

    override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
        CredentialStoreResult.StorageUnavailable

    override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
        CredentialStoreResult.StorageUnavailable

    override fun write(
        credential: ProviderCredentialId,
        value: String
    ): CredentialStoreResult<Unit> = CredentialStoreResult.StorageUnavailable

    override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
        CredentialStoreResult.StorageUnavailable

    override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
        CredentialStoreResult.StorageUnavailable

    override fun resetForReentry(): CredentialStoreResult<Unit> =
        CredentialStoreResult.StorageUnavailable
}

class FakeUserProfileStore : UserProfileStore {
    override fun read(): ProfileStoreResult<com.tsfdroid.ai.core.security.UserProfile?> =
        ProfileStoreResult.Success(null)

    override fun write(profile: com.tsfdroid.ai.core.security.UserProfile): ProfileStoreResult<Unit> =
        ProfileStoreResult.Success(Unit)

    override fun migrateLegacyProfile(): ProfileStoreResult<Unit> =
        ProfileStoreResult.Success(Unit)

    override fun resetForReentry(): ProfileStoreResult<Unit> =
        ProfileStoreResult.Success(Unit)
}
