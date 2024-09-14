#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_zqy_1kb_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_zqy_1kb_MainActivity_stringFromJNI2(JNIEnv *env, jobject thiz) {
    std::string hello = "Test from C++";
    return env->NewStringUTF(hello.c_str());
}