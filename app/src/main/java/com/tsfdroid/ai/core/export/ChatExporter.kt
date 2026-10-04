package com.tsfdroid.ai.core.export

import android.content.Context
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.repository.ChatSession
import com.tsfdroid.ai.data.repository.ConversationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.4.0 chat export: builds and writes the raw-text (JSON) export of a chat
 * session — the debugging artifact the user hands over after a day of real
 * use. Everything the model did is in the file: the full reasoning trace,
 * how long it thought, every tool call (raw arguments the model sent, params
 * actually dispatched, redacted + capped results, per-call durations), the
 * visible step trace, usage, and the model identity.
 *
 * WHERE THE FILE LANDS: `<external files dir>/workspace/Exports/` — the
 * app's own zero-permission workspace (the same root the agent's
 * WRITE_FILE/CREATE_PDF artifacts live in, and the directory the E2E suite
 * pulls). Exports deliberately do NOT route through the optional SAF custom
 * folder: the default workspace is deterministic, adb-pullable, and what
 * the CI artifacts expect.
 */
@Singleton
class ChatExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepository: ConversationRepository
) {

    data class ExportResult(val file: File, val messageCount: Int, val byteSize: Long)

    /**
     * Exports one session. The session must exist; messages may be empty (the
     * file is still written — an empty chat is a valid, honest export).
     * Returns null only when the session id does not resolve.
     */
    suspend fun exportSession(sessionId: String): ExportResult? {
        val session = conversationRepository.sessions.first()
            .firstOrNull { it.id == sessionId }
            ?: return null
        val messages: List<ChatMessage> = conversationRepository.getMessagesOnce(sessionId)
        val appInfo = appInfo()
        val exportedAt = System.currentTimeMillis()
        val document = ChatExportFormat.buildSessionDocument(session, messages, appInfo, exportedAt)

        val dir = File(storageWorkspaceRoot(), "Exports").apply { mkdirs() }
        val file = File(dir, ChatExportFormat.suggestFileName(session.title, exportedAt))
        file.writeText(document)

        return ExportResult(file = file, messageCount = messages.size, byteSize = file.length())
    }

    /** The same default workspace root StorageWorkspaceProvider resolves. */
    private fun storageWorkspaceRoot(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "workspace")
    }

    private fun appInfo(): ChatExportFormat.AppInfo = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "unknown"
        val versionCode = info.longVersionCode.toInt()
        ChatExportFormat.AppInfo(versionName, versionCode)
    } catch (_: Exception) {
        ChatExportFormat.AppInfo("unknown", 0)
    }
}
