/* Minimal win32 jni_md.h for cross-compiling libiroh_fsr with mingw-w64 (the Linux JDK ships only include/linux/jni_md.h).
   The JNI machine-dependent types are fixed by the JNI specification. Written for Iroh. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef long jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
