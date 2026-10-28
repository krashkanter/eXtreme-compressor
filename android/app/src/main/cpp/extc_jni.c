#include <jni.h>
#include <android/bitmap.h>
#include <pthread.h>
#include "codec.h"

typedef struct {
    JNIEnv *env;
    jobject flag;
    jmethodID get;
} CancelContext;

static pthread_mutex_t codec_lock = PTHREAD_MUTEX_INITIALIZER;

static void fail(JNIEnv *env, const char *message) {
    if(!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/io/IOException");
        if(type) {
            (*env)->ThrowNew(env, type, message);
        }
    }
}

static int is_cancelled(void *value) {
    CancelContext *context = value;
    return (*context->env)->CallBooleanMethod(context->env, context->flag, context->get) ||
           (*context->env)->ExceptionCheck(context->env);
}

static CancelContext cancel_context(JNIEnv *env, jobject flag) {
    jclass type = (*env)->GetObjectClass(env, flag);
    CancelContext context = {env, flag, (*env)->GetMethodID(env, type, "get", "()Z")};
    (*env)->DeleteLocalRef(env, type);
    return context;
}

JNIEXPORT void JNICALL Java_com_krashkanter_extc_NativeCodec_compress(
    JNIEnv *env, jobject self, jobject bitmap, jstring output, jobject flag) {
    (void)self;
    AndroidBitmapInfo info = {0};
    if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
       info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
       (uint64_t)info.width * info.height > UINT64_C(16000000)) {
        fail(env, "Image is too large or has an unsupported pixel format.");
        return;
    }
    unsigned char *source = NULL;
    if(AndroidBitmap_lockPixels(env, bitmap, (void **)&source) != ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env, "Cannot read image pixels.");
        return;
    }
    Image image = {0};
    image.width = (int)info.width;
    image.height = (int)info.height;
    image.channels = 3;
    size_t count = (size_t)image.width * (size_t)image.height;
    image.pixels = malloc(count * 3);
    if(!image.pixels) {
        AndroidBitmap_unlockPixels(env, bitmap);
        fail(env, "Not enough memory to convert this image.");
        return;
    }
    int gray = 1;
    int premultiplied = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    for(int y = 0; y < image.height; y++) {
        unsigned char *row = source + (size_t)y * info.stride;
        for(int x = 0; x < image.width; x++) {
            size_t pos = ((size_t)y * (size_t)image.width + (size_t)x) * 3;
            int alpha = row[x * 4 + 3];
            for(int channel = 0; channel < 3; channel++) {
                int value = row[x * 4 + channel];
                value = premultiplied ? value + 255 - alpha : (value * alpha + 127) / 255 + 255 - alpha;
                image.pixels[pos + (size_t)channel] = (unsigned char)value;
            }
            if(image.pixels[pos] != image.pixels[pos + 1] || image.pixels[pos] != image.pixels[pos + 2]) {
                gray = 0;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if(gray) {
        for(size_t i = 0; i < count; i++) {
            image.pixels[i] = image.pixels[i * 3];
        }
        image.channels = 1;
    }
    const char *path = (*env)->GetStringUTFChars(env, output, NULL);
    if(!path) {
        free(image.pixels);
        return;
    }
    FILE *file = fopen(path, "w+b");
    CancelContext context = cancel_context(env, flag);
    CompressionStats stats;
    int ok = 0;
    if(file) {
        pthread_mutex_lock(&codec_lock);
        ok = compress_image_controlled(file, &image, 8, 4, &stats, is_cancelled, &context);
        pthread_mutex_unlock(&codec_lock);
        if(fclose(file) != 0) {
            ok = 0;
        }
    }
    free(image.pixels);
    if(!ok) {
        remove(path);
        fail(env, is_cancelled(&context) ? "Conversion cancelled." : "Could not encode the image.");
    }
    (*env)->ReleaseStringUTFChars(env, output, path);
}

JNIEXPORT jobject JNICALL Java_com_krashkanter_extc_NativeCodec_decompress(
    JNIEnv *env, jobject self, jstring input, jobject flag, jlong max_pixels) {
    (void)self;
    const char *path = (*env)->GetStringUTFChars(env, input, NULL);
    if(!path) {
        return NULL;
    }
    FILE *file = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, input, path);
    if(!file) {
        fail(env, "Cannot open the EXTC file.");
        return NULL;
    }
    ExtcHeader header;
    if(!read_extc_header(file, &header)) {
        fclose(file);
        fail(env, header.version > EXTC_FORMAT_VERSION ? "This EXTC version needs a newer app." :
             "Invalid, unsupported, or non-image EXTC file.");
        return NULL;
    }
    if((uint64_t)header.width * (uint64_t)header.height > (uint64_t)max_pixels) {
        fclose(file);
        fail(env, "This image is too large to view safely on this device.");
        return NULL;
    }
    rewind(file);
    Image image = {0};
    CancelContext context = cancel_context(env, flag);
    pthread_mutex_lock(&codec_lock);
    int ok = decompress_image_controlled(file, &image, is_cancelled, &context);
    pthread_mutex_unlock(&codec_lock);
    fclose(file);
    if(!ok) {
        fail(env, is_cancelled(&context) ? "Opening cancelled." : "The EXTC image is incomplete or damaged.");
        return NULL;
    }
    jclass bitmap_class = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    jfieldID field = (*env)->GetStaticFieldID(env, config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = (*env)->GetStaticObjectField(env, config_class, field);
    jmethodID create = (*env)->GetStaticMethodID(env, bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = (*env)->CallStaticObjectMethod(env, bitmap_class, create, image.width, image.height, config);
    AndroidBitmapInfo info = {0};
    unsigned char *target = NULL;
    if(bitmap && AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
       AndroidBitmap_lockPixels(env, bitmap, (void **)&target) == ANDROID_BITMAP_RESULT_SUCCESS) {
        for(int y = 0; y < image.height; y++) {
            unsigned char *row = target + (size_t)y * info.stride;
            for(int x = 0; x < image.width; x++) {
                size_t pos = ((size_t)y * (size_t)image.width + (size_t)x) * (size_t)image.channels;
                row[x * 4] = image.pixels[pos];
                row[x * 4 + 1] = image.pixels[pos + (image.channels == 3 ? 1 : 0)];
                row[x * 4 + 2] = image.pixels[pos + (image.channels == 3 ? 2 : 0)];
                row[x * 4 + 3] = 255;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    } else {
        bitmap = NULL;
        fail(env, "Not enough memory to display this image.");
    }
    free(image.pixels);
    return bitmap;
}
