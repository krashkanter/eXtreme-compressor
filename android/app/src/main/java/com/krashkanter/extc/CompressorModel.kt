package com.krashkanter.extc

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

const val EXTC_MIME = "application/vnd.krashkanter.extc"

data class ConvertedImage(
    val file: File,
    val name: String,
    val sourceBytes: Long,
    val elapsedMillis: Long,
    val width: Int,
    val height: Int,
    val decodedBytes: Long
)

data class BatchInput(val uri: Uri, val relativeName: String)
data class BatchSelection(val name: String, val inputs: List<BatchInput>)
data class BatchSummary(val saved: Int, val failed: Int, val cancelled: Boolean, val elapsedMillis: Long)
data class OpenedImage(val bitmap: Bitmap, val name: String, val version: Int, val bytes: Long, val decodedBytes: Long, val sourceBytes: Long?)
data class BusyState(val label: String, val completed: Int = 0, val total: Int = 0)
data class AppState(
    val busy: BusyState? = null,
    val result: ConvertedImage? = null,
    val batch: BatchSelection? = null,
    val summary: BatchSummary? = null,
    val viewer: OpenedImage? = null,
    val error: String? = null,
    val notice: String? = null
)

class CompressorModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var cancellation = AtomicBoolean(false)
    private val convertedDirectory = File(app.cacheDir, "converted").apply { mkdirs() }
    private val maxPixels = min(16_000_000L, Runtime.getRuntime().maxMemory() / 20)

    init {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        convertedDirectory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun begin(label: String): AtomicBoolean? {
        if(mutableState.value.busy != null) return null
        cancellation = AtomicBoolean(false)
        mutableState.value = mutableState.value.copy(busy = BusyState(label), error = null, notice = null, summary = null)
        return cancellation
    }

    fun cancel() {
        cancellation.set(true)
        if(mutableState.value.busy == null) {
            mutableState.value = mutableState.value.copy(batch = null)
        } else {
            mutableState.value = mutableState.value.copy(busy = mutableState.value.busy?.copy(label = "Cancelling..."))
        }
    }

    private fun checkCancelled(flag: AtomicBoolean) {
        if(flag.get()) throw IOException("Cancelled")
    }

    private fun fileName(uri: Uri): String {
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    private fun safeName(name: String): String {
        return name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").take(100).ifBlank { "image" }
    }

    private fun copyInput(uri: Uri, destination: File, flag: AtomicBoolean): Long {
        app.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while(true) {
                    checkCancelled(flag)
                    val count = input.read(buffer)
                    if(count < 0) break
                    total += count
                    if(total > 512L * 1024 * 1024) throw IOException("This file is too large to open safely.")
                    output.write(buffer, 0, count)
                }
                return total
            }
        }
        throw IOException("The selected app did not grant access to this file.")
    }

    private fun loadBitmap(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if(bounds.outWidth < 1 || bounds.outHeight < 1) throw IOException("This image format cannot be read.")
        if(bounds.outWidth.toLong() * bounds.outHeight > maxPixels) {
            throw IOException("This image is too large for this device. Choose an image below ${maxPixels / 1_000_000} megapixels.")
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("The image could not be decoded.")
        val bitmap = if(decoded.config == Bitmap.Config.ARGB_8888) decoded else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Not enough memory to open this image.")
            decoded.recycle()
            copy
        }
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if(matrix.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        if(rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun convert(uri: Uri, flag: AtomicBoolean): ConvertedImage {
        val started = SystemClock.elapsedRealtime()
        val source = File.createTempFile("import-", ".image", app.cacheDir)
        val name = safeName(fileName(uri)).substringBeforeLast('.') + ".extc"
        val destination = File(convertedDirectory, "${UUID.randomUUID()}-$name")
        var bitmap: Bitmap? = null
        try {
            val size = copyInput(uri, source, flag)
            bitmap = loadBitmap(source)
            checkCancelled(flag)
            NativeCodec.compress(bitmap, destination.path, flag, size)
            checkCancelled(flag)
            val channels = destination.inputStream().use { stream ->
                val header = ByteArray(24)
                java.io.DataInputStream(stream).readFully(header)
                header[22].toInt()
            }
            return ConvertedImage(destination, name, size, SystemClock.elapsedRealtime() - started, bitmap.width, bitmap.height,
                bitmap.width.toLong() * bitmap.height * channels)
        } catch(error: Throwable) {
            destination.delete()
            throw error
        } finally {
            bitmap?.recycle()
            source.delete()
        }
    }

    fun convertImage(uri: Uri) {
        val flag = begin("Converting image...") ?: return
        mutableState.value.result?.file?.delete()
        mutableState.value = mutableState.value.copy(result = null, batch = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { convert(uri, flag) }
                mutableState.value = mutableState.value.copy(busy = null, result = result)
            } catch(error: Throwable) {
                report(error, flag)
            }
        }
    }

    fun selectBatch(uri: Uri) {
        val flag = begin("Finding images...") ?: return
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(app, uri) ?: throw IOException("Cannot read this folder.")
                    val inputs = mutableListOf<BatchInput>()
                    fun scan(folder: DocumentFile, prefix: String, depth: Int) {
                        checkCancelled(flag)
                        if(depth > 32 || inputs.size > 10000) throw IOException("This folder contains too many files or nested folders.")
                        for(file in folder.listFiles()) {
                            checkCancelled(flag)
                            val name = file.name ?: continue
                            if(file.isDirectory) scan(file, "$prefix$name/", depth + 1)
                            else if(file.type?.startsWith("image/") == true || name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "bmp", "webp", "heic", "heif", "avif")) {
                                inputs.add(BatchInput(file.uri, "$prefix$name"))
                            if(inputs.size > 10000) throw IOException("Select a folder with at most 10,000 images.")
                            }
                        }
                    }
                    scan(root, "", 0)
                    if(inputs.isEmpty()) throw IOException("No readable images were found in this folder.")
                    BatchSelection(root.name ?: "Selected folder", inputs)
                }
                mutableState.value = mutableState.value.copy(busy = null, batch = batch)
            } catch(error: Throwable) {
                report(error, flag)
            }
        }
    }

    fun runBatch(destination: Uri) {
        val selection = mutableState.value.batch ?: return
        val flag = begin("Preparing folder...") ?: return
        mutableState.value = mutableState.value.copy(batch = null)
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            var saved = 0
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(app, destination) ?: throw IOException("Cannot write to this folder.")
                    if(!root.canWrite()) throw IOException("This folder is read-only. Choose another folder.")
                    for((index, item) in selection.inputs.withIndex()) {
                        if(flag.get()) break
                        mutableState.value = mutableState.value.copy(busy = BusyState(item.relativeName, index, selection.inputs.size))
                        var converted: ConvertedImage? = null
                        var created: DocumentFile? = null
                        try {
                            converted = convert(item.uri, flag)
                            var folder = root
                            val parts = item.relativeName.split('/')
                            for(part in parts.dropLast(1)) {
                                val name = safeName(part)
                                val existing = folder.findFile(name)
                                folder = if(existing?.isDirectory == true) existing else folder.createDirectory(name)
                                    ?: throw IOException("Cannot create an output subfolder.")
                            }
                            var name = converted.name
                            var suffix = 1
                            while(folder.findFile(name) != null) {
                                name = converted.name.removeSuffix(".extc") + " (${suffix++}).extc"
                            }
                            created = folder.createFile(EXTC_MIME, name) ?: throw IOException("Cannot create output file.")
                            writeOutput(created.uri, converted.file, flag)
                            saved++
                        } catch(error: Throwable) {
                            created?.delete()
                            if(flag.get()) break
                            failed++
                        } finally {
                            converted?.file?.delete()
                        }
                    }
                }
                mutableState.value = mutableState.value.copy(busy = null,
                    summary = BatchSummary(saved, failed, flag.get(), SystemClock.elapsedRealtime() - started))
            } catch(error: Throwable) {
                report(error, flag)
            }
        }
    }

    private fun writeOutput(uri: Uri, file: File, flag: AtomicBoolean) {
        app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while(true) {
                    checkCancelled(flag)
                    val count = input.read(buffer)
                    if(count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            return
        }
        throw IOException("The selected folder cannot be written to.")
    }

    fun saveResult(uri: Uri) {
        val converted = mutableState.value.result ?: return
        val flag = begin("Saving file...") ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeOutput(uri, converted.file, flag) }
                converted.file.delete()
                mutableState.value = mutableState.value.copy(busy = null, result = null, notice = "Saved")
            } catch(error: Throwable) {
                runCatching { app.contentResolver.delete(uri, null, null) }
                report(error, flag)
            }
        }
    }

    fun copyResult() {
        val converted = mutableState.value.result ?: return
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", converted.file, converted.name)
        val clip = ClipData.newUri(app.contentResolver, converted.name, uri)
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        mutableState.value = mutableState.value.copy(result = null, notice = "Copied")
    }

    fun discardResult() {
        mutableState.value.result?.file?.delete()
        mutableState.value = mutableState.value.copy(result = null)
    }

    fun openImage(uri: Uri) {
        val flag = begin("Opening EXTC image...") ?: return
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) { File.createTempFile("view-", ".extc", app.cacheDir) }
            try {
                val opened = withContext(Dispatchers.IO) {
                    val size = copyInput(uri, source, flag)
                    val version = source.inputStream().use { input ->
                        val header = ByteArray(8)
                        if(input.read(header) != 8 || String(header, 0, 4, Charsets.US_ASCII) != "EXTC") {
                            throw IOException("This is not an EXTC file.")
                        }
                        val version = header[4].toInt() and 255
                        if(version !in 1..3) throw IOException("EXTC version $version is not supported. A newer app may be needed.")
                        if(version >= 2 && header[5].toInt() != 1) throw IOException("This EXTC file contains another media type, not an image.")
                        version
                    }
                    val metadata = source.inputStream().use { input ->
                        val header = ByteArray(if(version == 3) 32 else if(version == 2) 24 else 20)
                        java.io.DataInputStream(input).readFully(header)
                        val channels = if(version == 1) { if(header[7].toInt() == 3) 3 else 1 } else header[22].toInt()
                        val original = if(version == 3) java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(24).takeIf { it > 0 } else null
                        channels to original
                    }
                    val bitmap = NativeCodec.decompress(source.path, flag, maxPixels)
                    OpenedImage(bitmap, fileName(uri), version, size, bitmap.width.toLong() * bitmap.height * metadata.first, metadata.second)
                }
                mutableState.value = mutableState.value.copy(busy = null, viewer = opened)
            } catch(error: Throwable) {
                report(error, flag)
            } finally {
                withContext(Dispatchers.IO) { source.delete() }
            }
        }
    }

    private fun report(error: Throwable, flag: AtomicBoolean) {
        val message = if(error is OutOfMemoryError) "Not enough memory to process this image safely." else error.message ?: "The file could not be processed."
        mutableState.value = mutableState.value.copy(busy = null, error = if(flag.get()) null else message)
    }

    fun closeViewer() { mutableState.value = mutableState.value.copy(viewer = null) }
    fun dismissMessage() { mutableState.value = mutableState.value.copy(error = null, summary = null, notice = null) }
    override fun onCleared() { cancellation.set(true) }
}
