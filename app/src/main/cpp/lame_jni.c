#include <jni.h>
#include <stdlib.h>
#include "lame.h"

static lame_global_flags *glf = NULL;

JNIEXPORT void JNICALL
Java_flai_app_Lame_init(JNIEnv *env, jclass cls, jint rate, jint channels, jint brate) {
    (void) cls;
    if (glf) lame_close(glf);
    glf = lame_init();
    lame_set_in_samplerate(glf, rate);
    lame_set_num_channels(glf, channels);
    lame_set_brate(glf, brate);
    lame_set_quality(glf, 5);
    lame_init_params(glf);
}

JNIEXPORT jbyteArray JNICALL
Java_flai_app_Lame_encode(JNIEnv *env, jclass cls, jbyteArray jpcm) {
    (void) cls;
    if (!glf) return NULL;
    jsize n = (*env)->GetArrayLength(env, jpcm);
    jbyte *pcm = (*env)->GetByteArrayElements(env, jpcm, NULL);
    int nsamp = n / 2;
    int cap = 5 * nsamp / 4 + 7200;
    unsigned char *out = (unsigned char *) malloc(cap);
    int w = lame_encode_buffer(glf, (short int *) pcm, NULL, nsamp, out, cap);
    (*env)->ReleaseByteArrayElements(env, jpcm, pcm, JNI_ABORT);
    jbyteArray r = NULL;
    if (w > 0) {
        r = (*env)->NewByteArray(env, w);
        (*env)->SetByteArrayRegion(env, r, 0, w, (const jbyte *) out);
    }
    free(out);
    return r;
}

JNIEXPORT jbyteArray JNICALL
Java_flai_app_Lame_flush(JNIEnv *env, jclass cls) {
    (void) cls;
    if (!glf) return NULL;
    unsigned char *out = (unsigned char *) malloc(7200);
    int w = lame_encode_flush(glf, out, 7200);
    jbyteArray r = NULL;
    if (w > 0) {
        r = (*env)->NewByteArray(env, w);
        (*env)->SetByteArrayRegion(env, r, 0, w, (const jbyte *) out);
    }
    free(out);
    lame_close(glf);
    glf = NULL;
    return r;
}
