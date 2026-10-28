package com.krashkanter.extc

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

class FixtureDocumentsProvider : DocumentsProvider() {
    private val base get() = File(requireNotNull(context).filesDir, "fixtures").apply { mkdirs() }
    override fun onCreate() = true
    private fun file(id: String): File {
        val target = File(base, id).canonicalFile
        require(target.path.startsWith(base.canonicalPath + File.separator))
        return target
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS))
        for(id in listOf("source", "output")) {
            File(base, id).mkdirs()
            cursor.newRow().add(Root.COLUMN_ROOT_ID, id).add(Root.COLUMN_DOCUMENT_ID, id)
                .add(Root.COLUMN_TITLE, "EXTC test $id")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
        return cursor
    }
    private fun row(cursor: MatrixCursor, id: String) {
        val f = file(id)
        val mime = if(f.isDirectory) Document.MIME_TYPE_DIR else if(f.extension == "png") "image/png" else EXTC_MIME
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_SIZE, f.length())
            .add(Document.COLUMN_FLAGS, if(f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
    }
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).also { row(it, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).also { cursor ->
        file(parentDocumentId).listFiles()?.forEach { row(cursor, "$parentDocumentId/${it.name}") }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && !displayName.contains('\\'))
        val id = "$parentDocumentId/$displayName"
        if(mimeType == Document.MIME_TYPE_DIR) file(id).mkdirs() else file(id).createNewFile()
        return id
    }
    override fun deleteDocument(documentId: String) { file(documentId).delete() }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
}
