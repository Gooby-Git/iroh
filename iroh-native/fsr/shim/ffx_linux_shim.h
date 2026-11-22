// Force-included (-include) when compiling the AMD FidelityFX SDK 1.1.4 with gcc/clang on Linux.
// Supplies the MSVC-only functions the SDK sources call. Clean-room, written for Iroh.
#pragma once
#include <wchar.h>
#include <string.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <float.h>
#include <limits.h>
#ifdef __cplusplus
#include <cmath>
#include <cstring>
#include <cwchar>
#include <string>
#include <locale>
#include <algorithm>
#include <mutex>
#include <vector>
#include <map>
#include <cstdint>
#define _countof(a) (sizeof(a) / sizeof((a)[0]))
#define UNREFERENCED_PARAMETER(x) (void)(x)
template <size_t N> inline int wcscpy_s(wchar_t (&dst)[N], const wchar_t* src) { wcsncpy(dst, src, N - 1); dst[N - 1] = 0; return 0; }
inline int wcscpy_s(wchar_t* dst, size_t n, const wchar_t* src) { wcsncpy(dst, src, n - 1); dst[n - 1] = 0; return 0; }
template <size_t N> inline int strcpy_s(char (&dst)[N], const char* src) { strncpy(dst, src, N - 1); dst[N - 1] = 0; return 0; }
inline int strcpy_s(char* dst, size_t n, const char* src) { strncpy(dst, src, n - 1); dst[n - 1] = 0; return 0; }
template <size_t N> inline int wcscat_s(wchar_t (&dst)[N], const wchar_t* src) { wcsncat(dst, src, N - wcslen(dst) - 1); return 0; }
#define __debugbreak() ((void)0)
#ifndef FFX_UNUSED
#define FFX_UNUSED(x) (void)(x)
#endif
#endif
#ifdef __cplusplus
#include <cstdarg>
inline int sprintf_s(char* buf, size_t n, const char* fmt, ...) { va_list ap; va_start(ap, fmt); int r = vsnprintf(buf, n, fmt, ap); va_end(ap); return r; }
template <size_t N> inline int sprintf_s(char (&buf)[N], const char* fmt, ...) { va_list ap; va_start(ap, fmt); int r = vsnprintf(buf, N, fmt, ap); va_end(ap); return r; }
#endif
