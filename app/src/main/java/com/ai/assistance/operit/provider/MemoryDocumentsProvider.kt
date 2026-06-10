package com.ai.assistance.operit.provider

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

class MemoryDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true
    
    override fun queryRoots(projection: Array<out String>?): Cursor {
        return MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_SUMMARY)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "memory").add(Root.COLUMN_SUMMARY, "Memory")
        }
    }
    
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME))
    }
    
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        return MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)).apply {
            newRow().add(Document.COLUMN_DOCUMENT_ID, documentId).add(Document.COLUMN_DISPLAY_NAME, documentId)
        }
    }
    
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = null

}
