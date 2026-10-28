package com.krashkanter.extc

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CodecInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun image(width: Int = 40, height: Int = 28): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for(y in 0 until height) for(x in 0 until width) bitmap.setPixel(x, y,
            android.graphics.Color.rgb((x * 7) % 256, (y * 13) % 256, (x + y) * 3 % 256))
        return bitmap
    }
    private fun matches(a: Bitmap, b: Bitmap) {
        Assert.assertEquals(a.width, b.width)
        Assert.assertEquals(a.height, b.height)
        for(y in 0 until a.height) for(x in 0 until a.width) Assert.assertEquals(a.getPixel(x,y), b.getPixel(x,y))
    }
    private fun waitModel(model: CompressorModel) {
        compose.waitUntil(30_000) { model.state.value.busy == null }
    }
    private fun enableFixtures(enabled: Boolean) {
        app.packageManager.setComponentEnabledSetting(ComponentName(app, FixtureDocumentsProvider::class.java),
            if(enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    @Test fun nativeRoundTripVersionsAndCorruption() {
        val original = image()
        val file = File(app.cacheDir, "native-test.extc")
        NativeCodec.compress(original, file.path, AtomicBoolean(false), 12345)
        val bytes = file.readBytes()
        Assert.assertEquals(3, bytes[4].toInt())
        Assert.assertEquals(1, bytes[5].toInt())
        Assert.assertEquals(32, bytes[7].toInt())
        Assert.assertEquals(12345L,java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(24))
        matches(original, NativeCodec.decompress(file.path, AtomicBoolean(false), 16_000_000))
        val legacy = bytes.copyOfRange(0,20) + bytes.copyOfRange(32, bytes.size)
        legacy[4] = 1; legacy[5] = 4; legacy[6] = 8; legacy[7] = 3
        file.writeBytes(legacy)
        matches(original, NativeCodec.decompress(file.path, AtomicBoolean(false), 16_000_000))
        val versionTwo = bytes.copyOfRange(0,24) + bytes.copyOfRange(32,bytes.size)
        versionTwo[4] = 2; versionTwo[7] = 24
        file.writeBytes(versionTwo)
        matches(original, NativeCodec.decompress(file.path, AtomicBoolean(false),16_000_000))
        for(mutated in listOf(bytes.copyOf().apply { this[4] = 99 }, bytes.copyOf().apply { this[5] = 2 },
            bytes.copyOf().apply { this[16] = (this[16].toInt() xor 1).toByte() }, bytes.copyOf(21))) {
            file.writeBytes(mutated)
            Assert.assertThrows(IOException::class.java) { NativeCodec.decompress(file.path, AtomicBoolean(false), 16_000_000) }
        }
        file.writeBytes(bytes)
        Assert.assertThrows(IOException::class.java) { NativeCodec.decompress(file.path, AtomicBoolean(false), 1) }
        Assert.assertThrows(IOException::class.java) { NativeCodec.compress(original, file.path, AtomicBoolean(true)) }
        Assert.assertFalse(file.exists())
        original.recycle()
    }

    @Test fun shareIntentOffersSaveCopyAndDiscard() {
        val directory = File(app.cacheDir,"converted").apply { mkdirs() }
        val source = File(directory, "shared-test.png")
        source.outputStream().use { image().compress(Bitmap.CompressFormat.PNG,100,it) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files",source)
        app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Converted").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Save").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy").assertIsDisplayed()
        compose.onNodeWithText("Time").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy").performClick()
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).uri
        Assert.assertNotNull(copied)
        app.contentResolver.openInputStream(copied)!!.use { input ->
            val header = ByteArray(5)
            Assert.assertEquals(5,input.read(header))
            Assert.assertEquals("EXTC",String(header,0,4,Charsets.US_ASCII))
            Assert.assertEquals(3,header[4].toInt())
        }
        app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Discard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Discard").performClick()
        compose.onNodeWithText("Pick image").assertIsDisplayed()
    }

    @Test fun storageAccessFrameworkBatchSaveViewerAndCancellation() {
        enableFixtures(true)
        try {
            val root = File(app.filesDir, "fixtures")
            val source = File(root,"source").apply { mkdirs() }
            val nested = File(source,"nested").apply { mkdirs() }
            val output = File(root,"output").apply { mkdirs() }
            val bitmap = image()
            File(source,"first.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(nested,"second.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val model = CompressorModel(app)
            val inputTree = DocumentsContract.buildTreeDocumentUri("com.krashkanter.extc.fixtures","source")
            val outputTree = DocumentsContract.buildTreeDocumentUri("com.krashkanter.extc.fixtures","output")
            app.grantUriPermission(app.packageName, inputTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            app.grantUriPermission(app.packageName, outputTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            compose.runOnUiThread { model.selectBatch(inputTree) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertEquals(2,model.state.value.batch?.inputs?.size)
            compose.runOnUiThread { model.runBatch(outputTree) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertEquals(2,model.state.value.summary?.saved)
            Assert.assertTrue(File(output,"first.extc").exists())
            Assert.assertTrue(File(output,"nested/second.extc").exists())
            matches(bitmap,NativeCodec.decompress(File(output,"first.extc").path,AtomicBoolean(false),16_000_000))
            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(inputTree,"source/first.png")
            compose.runOnUiThread { model.convertImage(sourceUri) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertNotNull(model.state.value.result)
            val saveUri = DocumentsContract.createDocument(app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(outputTree,"output"), EXTC_MIME,"saved.extc")!!
            compose.runOnUiThread { model.saveResult(saveUri) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertTrue(File(output,"saved.extc").exists())
            compose.runOnUiThread { model.openImage(saveUri) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertEquals(3,model.state.value.viewer?.version)
            Assert.assertEquals(File(source,"first.png").length(), model.state.value.viewer?.sourceBytes)
            Assert.assertEquals(40L*28*3, model.state.value.viewer?.decodedBytes)
            matches(bitmap,model.state.value.viewer!!.bitmap)
            compose.runOnUiThread { model.selectBatch(inputTree) }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            compose.runOnUiThread { model.runBatch(outputTree); model.cancel() }
            waitModel(model)
            Assert.assertNull(model.state.value.error, model.state.value.error)
            Assert.assertEquals(true,model.state.value.summary?.cancelled)
            bitmap.recycle()
        } finally {
            enableFixtures(false)
        }
    }

    @Test fun seedPickerImage() {
        if(android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"EXTC sample.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/EXTC")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
            app.contentResolver.openOutputStream(uri)!!.use { image(160,120).compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }

    @Test fun viewIntentAndThemeSwitch() {
        val directory = File(app.cacheDir,"converted").apply { mkdirs() }
        val file = File(directory,"view-test.extc")
        NativeCodec.compress(image(),file.path,AtomicBoolean(false))
        val uri = FileProvider.getUriForFile(app,"${app.packageName}.files",file)
        app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri,EXTC_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Fit image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Fit image").assertIsDisplayed()
        compose.onNodeWithText("Decoded pixels").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        val light = compose.onAllNodesWithContentDescription("Switch to light mode").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithContentDescription(if(light) "Switch to light mode" else "Switch to dark mode").performClick()
        compose.onNodeWithContentDescription(if(light) "Switch to dark mode" else "Switch to light mode").assertExists()
    }
}
