# libiroh_fsr: AMD FidelityFX SDK v1.1.4 (MIT, patched) FSR 3.1 upscaler + frame interpolation + optical flow on the
# SDK's Vulkan backend, with the Iroh JNI shim. Driven by ../build-fsr.sh (fetches + patches the SDK, generates shader
# permutation headers with the system glslangValidator instead of AMD's Windows-only FidelityFX_SC.exe).
cmake_minimum_required(VERSION 3.23)
project(iroh_fsr LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(IROH_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/sdk-src" CACHE PATH "patched SDK root (fetch-sdk.sh output)")
set(IROH_GEN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/gen" CACHE PATH "generated permutation headers (gen_permutations.py output)")
set(IROH_JNI_INCLUDE "" CACHE PATH "directory with jni.h (JDK include)")
set(IROH_VK_INCLUDE "" CACHE PATH "directory with vulkan/vulkan.h (cross builds)")
set(SDK ${IROH_SDK_DIR}/sdk)
if(NOT EXISTS ${SDK}/include)
  message(FATAL_ERROR "SDK not fetched: run fetch-sdk.sh")
endif()
set(SRCS
  ${SDK}/src/backends/vk/ffx_vk.cpp
  ${SDK}/src/backends/shared/ffx_shader_blobs.cpp
  ${SDK}/src/backends/shared/blob_accessors/ffx_fsr3upscaler_shaderblobs.cpp
  ${SDK}/src/backends/shared/blob_accessors/ffx_frameinterpolation_shaderblobs.cpp
  ${SDK}/src/backends/shared/blob_accessors/ffx_opticalflow_shaderblobs.cpp
  ${SDK}/src/components/fsr3upscaler/ffx_fsr3upscaler.cpp
  ${SDK}/src/components/frameinterpolation/ffx_frameinterpolation.cpp
  ${SDK}/src/components/opticalflow/ffx_opticalflow.cpp
  ${SDK}/src/shared/ffx_assert.cpp ${SDK}/src/shared/ffx_message.cpp
  ${SDK}/src/shared/ffx_object_management.cpp ${SDK}/src/shared/ffx_breadcrumbs_list.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/iroh_fsr_jni.cpp)
if(WIN32)
  # the Win32 frame-interpolation swapchain is not built (see docs/P4-PLAN.md); a stub satisfies ffx_vk.cpp
  list(APPEND SRCS ${CMAKE_CURRENT_SOURCE_DIR}/shim/stub_swapchain.cpp)
else()
  list(APPEND SRCS ${CMAKE_CURRENT_SOURCE_DIR}/shim/stub_swapchain.cpp)
endif()
add_library(iroh_fsr SHARED ${SRCS})
if(WIN32)
  set_target_properties(iroh_fsr PROPERTIES PREFIX "" OUTPUT_NAME iroh_fsr)
else()
  set_target_properties(iroh_fsr PROPERTIES PREFIX "lib" OUTPUT_NAME iroh_fsr)
endif()
target_include_directories(iroh_fsr PRIVATE ${SDK}/include ${SDK}/src/shared ${SDK}/src/backends/shared
  ${SDK}/src/backends/shared/blob_accessors ${SDK}/src/components ${SDK}/src/components/fsr3upscaler
  ${SDK}/src/components/frameinterpolation ${SDK}/src/components/opticalflow
  ${IROH_GEN_DIR}/fsr3upscaler ${IROH_GEN_DIR}/frameinterpolation ${IROH_GEN_DIR}/opticalflow ${SDK}/src/backends/vk)
if(IROH_JNI_INCLUDE)
  target_include_directories(iroh_fsr PRIVATE ${IROH_JNI_INCLUDE})
endif()
# the SDK dispatches shader blobs / effects behind these per-effect macros (AMD CMake defines them); without them the blob table is empty
target_compile_definitions(iroh_fsr PRIVATE FFX_BUILD_AS_DLL FFX_FSR3UPSCALER FFX_FI FFX_OF "FFX_SDK_DEFAULT_CONTEXT_SIZE=(1024*256)")
if(WIN32)
  target_include_directories(iroh_fsr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim/win32 ${IROH_VK_INCLUDE})
  target_compile_options(iroh_fsr PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/ffx_mingw_shim.h -fpermissive -w)
  target_link_options(iroh_fsr PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--unresolved-symbols=report-all)
  target_link_libraries(iroh_fsr PRIVATE ${IROH_VULKAN_IMPORT_LIB})
else()
  find_package(Vulkan REQUIRED)
  target_compile_definitions(iroh_fsr PRIVATE FFX_GCC)
  target_compile_options(iroh_fsr PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/ffx_linux_shim.h -fpermissive -w -fvisibility=hidden)
  target_link_options(iroh_fsr PRIVATE -static-libstdc++ -static-libgcc -Wl,--no-undefined -Wl,--exclude-libs,ALL)
  target_link_libraries(iroh_fsr PRIVATE Vulkan::Vulkan dl pthread)
endif()
