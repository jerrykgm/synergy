package com.example.synergyclient.data

import android.content.Context
import java.io.File

object NotesManager {
    private const val CLIP_FILE = "synergy_clip_history.txt"
    private val clipHistory = mutableListOf<String>()

    private fun getDocsDir(context: Context): File {
        val dir = File(context.filesDir, "documents")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // ── Text File Editor Management ──────────────────────────────────────────

    fun getTextFiles(context: Context): List<String> {
        val dir = getDocsDir(context)
        val files = dir.listFiles { _, name -> name.endsWith(".txt") } ?: return emptyList()
        return files.map { it.name }.sorted()
    }

    fun readFile(context: Context, filename: String): String {
        return try {
            val file = File(getDocsDir(context), filename)
            if (file.exists()) {
                file.readText()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun saveFile(context: Context, filename: String, content: String) {
        try {
            val file = File(getDocsDir(context), filename)
            file.writeText(content)
        } catch (_: Exception) {}
    }

    fun deleteFile(context: Context, filename: String) {
        try {
            val file = File(getDocsDir(context), filename)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    // ── Clipboard History Management ─────────────────────────────────────────

    fun getClipHistory(context: Context): List<String> {
        synchronized(clipHistory) {
            if (clipHistory.isEmpty()) {
                loadClipHistory(context)
            }
            return clipHistory.toList()
        }
    }

    fun addClipItem(context: Context, item: String) {
        val trimmed = item.trim()
        if (trimmed.isEmpty()) return
        synchronized(clipHistory) {
            clipHistory.remove(trimmed)
            clipHistory.add(0, trimmed)
            if (clipHistory.size > 50) {
                clipHistory.removeAt(clipHistory.size - 1)
            }
            saveClipHistory(context)
        }
    }

    fun clearClipHistory(context: Context) {
        synchronized(clipHistory) {
            clipHistory.clear()
            saveClipHistory(context)
        }
    }

    private fun loadClipHistory(context: Context) {
        try {
            val file = File(context.filesDir, CLIP_FILE)
            if (file.exists()) {
                clipHistory.clear()
                file.readLines().filter { it.isNotBlank() }.forEach { line ->
                    try {
                        val decoded = String(android.util.Base64.decode(line, android.util.Base64.DEFAULT))
                        clipHistory.add(decoded)
                    } catch (_: Exception) {
                        clipHistory.add(line)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveClipHistory(context: Context) {
        try {
            val file = File(context.filesDir, CLIP_FILE)
            file.printWriter().use { out ->
                clipHistory.forEach { item ->
                    val encoded = android.util.Base64.encodeToString(item.toByteArray(), android.util.Base64.NO_WRAP)
                    out.println(encoded)
                }
            }
        } catch (_: Exception) {}
    }
}
