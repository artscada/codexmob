package com.codex.android.data.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatSessionEntity(
    val id: String,
    val title: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = "",
    val messageCount: Int = 0
)

@Serializable
data class ChatMessageEntity(
    val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val streaming: Boolean = false
)

@Serializable
data class ChatAppState(
    val selectedChatId: String? = null
)

@Serializable
data class ChatRepositoryState(
    val chats: List<ChatSessionEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val appState: ChatAppState = ChatAppState()
)
