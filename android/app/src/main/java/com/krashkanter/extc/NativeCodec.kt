package com.krashkanter.extc

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

object NativeCodec {
    init { System.loadLibrary("extc") }

    external fun compress(bitmap: Bitmap, output: String, cancelled: AtomicBoolean)
    external fun decompress(input: String, cancelled: AtomicBoolean, maxPixels: Long): Bitmap
}
