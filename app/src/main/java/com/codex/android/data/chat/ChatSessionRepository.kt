package com.codex.android.data.chat

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ChatSessionRepository private constructor(
    context: Context
) {
    companion object {
        private const val TAG = "ChatSessionRepository"
        private const val DEFAULT_CHAT_TITLE = "Новый диалог"

        @Volatile
        private var INSTANCE: ChatSessionRepository? = null

        fun getInstance(context: Context): ChatSessionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatSessionRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val storageFile = File(context.filesDir, "chat_sessions.json")
    private val lock = Any()

    @Volatile
    private var loaded = false
    private var state = ChatRepositoryState()

    fun ensureInitialChat(mode: String = "local"): ChatSessionEntity {
        synchronized(lock) {
            ensureLoadedLocked()
            val selected = state.appState.selectedChatId?.let { selectedId ->
                state.chats.firstOrNull { it.id == selectedId }
            }
            if (selected != null) {
                return selected
            }

            val firstExisting = state.chats.maxByOrNull { it.updatedAt }
            if (firstExisting != null) {
                state = state.copy(appState = ChatAppState(selectedChatId = firstExisting.id))
                saveLocked()
                return firstExisting
            }

            return createChatLocked(mode = mode)
        }
    }

    fun listChats(): List<ChatSessionEntity> {
        synchronized(lock) {
            ensureLoadedLocked()
            return state.chats.sortedByDescending { it.updatedAt }
        }
    }

    fun listMessages(chatId: String): List<ChatMessageEntity> {
        synchronized(lock) {
            ensureLoadedLocked()
            return state.messages
                .filter { it.chatId == chatId }
                .sortedBy { it.createdAt }
        }
    }

    fun createChat(title: String? = null, mode: String = "local"): ChatSessionEntity {
        synchronized(lock) {
            ensureLoadedLocked()
            return createChatLocked(title = title, mode = mode)
        }
    }

    fun renameChat(chatId: String, title: String): Boolean {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return false

        synchronized(lock) {
            ensureLoadedLocked()
            val target = state.chats.firstOrNull { it.id == chatId } ?: return false
            val updated = target.copy(title = cleanTitle, updatedAt = System.currentTimeMillis())
            state = state.copy(chats = state.chats.map { if (it.id == chatId) updated else it })
            saveLocked()
            return true
        }
    }

    fun deleteChat(chatId: String): String? {
        synchronized(lock) {
            ensureLoadedLocked()
            if (state.chats.none { it.id == chatId }) return state.appState.selectedChatId

            val remainingChats = state.chats.filterNot { it.id == chatId }
            val remainingMessages = state.messages.filterNot { it.chatId == chatId }
            val nextSelectedId = when {
                remainingChats.isEmpty() -> null
                state.appState.selectedChatId == chatId -> remainingChats.maxByOrNull { it.updatedAt }?.id
                else -> state.appState.selectedChatId
            }

            state = state.copy(
                chats = remainingChats,
                messages = remainingMessages,
                appState = ChatAppState(selectedChatId = nextSelectedId)
            )

            if (state.chats.isEmpty()) {
                return createChatLocked().id
            }

            saveLocked()
            return state.appState.selectedChatId
        }
    }

    fun clearChat(chatId: String): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            val target = state.chats.firstOrNull { it.id == chatId } ?: return false
            state = state.copy(
                chats = state.chats.map {
                    if (it.id == chatId) {
                        it.copy(
                            updatedAt = System.currentTimeMillis(),
                            lastMessagePreview = "",
                            messageCount = 0,
                            title = if (target.title.isBlank()) DEFAULT_CHAT_TITLE else target.title
                        )
                    } else {
                        it
                    }
                },
                messages = state.messages.filterNot { it.chatId == chatId }
            )
            saveLocked()
            return true
        }
    }

    fun appendMessage(
        chatId: String,
        role: String,
        content: String,
        streaming: Boolean = false
    ): ChatMessageEntity? {
        val normalized = content.replace("\u0000", "").trim()
        if (normalized.isBlank()) return null

        synchronized(lock) {
            ensureLoadedLocked()
            val existingChat = state.chats.firstOrNull { it.id == chatId } ?: ensureInitialChat()
            val now = System.currentTimeMillis()
            val message = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                chatId = existingChat.id,
                role = role,
                content = normalized,
                createdAt = now,
                streaming = streaming
            )
            val updatedMessages = state.messages + message
            val updatedChat = existingChat.copy(
                title = nextChatTitle(existingChat.title, role, normalized),
                updatedAt = now,
                lastMessagePreview = buildPreview(normalized),
                messageCount = updatedMessages.count { it.chatId == existingChat.id }
            )

            state = state.copy(
                chats = state.chats.map { if (it.id == existingChat.id) updatedChat else it },
                messages = updatedMessages,
                appState = ChatAppState(selectedChatId = existingChat.id)
            )
            saveLocked()
            return message
        }
    }

    fun setSelectedChat(chatId: String): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            if (state.chats.none { it.id == chatId }) return false
            state = state.copy(appState = ChatAppState(selectedChatId = chatId))
            saveLocked()
            return true
        }
    }

    fun getSelectedChatId(): String? {
        synchronized(lock) {
            ensureLoadedLocked()
            return state.appState.selectedChatId
        }
    }

    fun getBootstrapState(mode: String = "local"): ChatRepositoryState {
        synchronized(lock) {
            ensureLoadedLocked()
            ensureInitialChat(mode)
            return state.copy(
                chats = state.chats.sortedByDescending { it.updatedAt },
                messages = state.messages.sortedBy { it.createdAt }
            )
        }
    }

    private fun createChatLocked(title: String? = null, mode: String = "local"): ChatSessionEntity {
        val now = System.currentTimeMillis()
        val chat = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_CHAT_TITLE,
            mode = mode,
            createdAt = now,
            updatedAt = now
        )
        state = state.copy(
            chats = state.chats + chat,
            appState = ChatAppState(selectedChatId = chat.id)
        )
        saveLocked()
        return chat
    }

    private fun buildPreview(content: String): String {
        return content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
    }

    private fun nextChatTitle(currentTitle: String, role: String, content: String): String {
        if (role != "user") return currentTitle.ifBlank { DEFAULT_CHAT_TITLE }
        if (currentTitle.isNotBlank() && currentTitle != DEFAULT_CHAT_TITLE) return currentTitle

        return buildPreview(content)
            .take(48)
            .ifBlank { DEFAULT_CHAT_TITLE }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return

        state = try {
            if (storageFile.exists()) {
                json.decodeFromString<ChatRepositoryState>(storageFile.readText())
            } else {
                ChatRepositoryState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat sessions", e)
            ChatRepositoryState()
        }
        loaded = true
    }

    private fun saveLocked() {
        try {
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat sessions", e)
        }
    }
}
