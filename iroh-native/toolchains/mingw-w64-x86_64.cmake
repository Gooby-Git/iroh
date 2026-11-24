# CMake toolchain: cross-compile Iroh's natives for Windows x64 with mingw-w64 (GCC, posix or win32 threads both work
# because the DLLs link libgcc/libstdc++/winpthread statically). Use: cmake -DCMAKE_TOOLCHAIN_FILE=<this> ...
# Override the triple prefix with -DIROH_MINGW_TRIPLE=x86_64-w64-mingw32 (default) if your distro names it differently.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
if(NOT IROH_MINGW_TRIPLE)
  set(IROH_MINGW_TRIPLE x86_64-w64-mingw32)
endif()
set(CMAKE_C_COMPILER ${IROH_MINGW_TRIPLE}-gcc)
set(CMAKE_CXX_COMPILER ${IROH_MINGW_TRIPLE}-g++)
set(CMAKE_RC_COMPILER ${IROH_MINGW_TRIPLE}-windres)
set(CMAKE_FIND_ROOT_PATH /usr/${IROH_MINGW_TRIPLE})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
