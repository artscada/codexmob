package com.codex.android.runtime.linux

enum class LinuxRuntimeMode(val id: String) {
    PROOT("proot"),
    CHROOT("chroot");

    companion object {
        fun fromId(id: String?): LinuxRuntimeMode {
            val normalized = id?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: PROOT
        }
    }
}
