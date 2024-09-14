#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_zqy_1kb_MainActivity_stringFromJNI3(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++NEW";
    return env->NewStringUTF(hello.c_str());
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_zqy_1kb_MainActivity_stringFromJNI4(JNIEnv *env, jobject thiz) {
    std::string hello = "Test from C++new";
    return env->NewStringUTF(hello.c_str());
}