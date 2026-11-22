// Force-included for mingw-w64 cross builds of the SDK (MSVC-targeted sources need a few standard headers MSVC pulls in implicitly).
#pragma once
#ifndef MINGW_HAS_SECURE_API
#define MINGW_HAS_SECURE_API 1
#endif
#include <stdio.h>
#include <wchar.h>
#ifdef __cplusplus
#include <bit>
#include <new>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <string>
#include <vector>
#include <mutex>
#endif
