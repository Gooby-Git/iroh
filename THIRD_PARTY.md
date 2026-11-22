# Third-party code shipped inside Iroh binaries

## AMD FidelityFX SDK v1.1.4 (MIT), compiled into natives/<os>-<arch>/libiroh_fsr (iroh-native/)
Source: https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK, tag v1.1.4, commit c6efa6bf7f2027b3ec94f28578bb5965eabb9e55.
Fetched at build time by iroh-native/fsr/fetch-sdk.sh (not vendored; content hash pinned). Used: FSR 3.1 upscaler, frame
interpolation, optical flow, the SDK Vulkan backend, shared helpers, GLSL shaders (compiled to SPIR-V by glslang at build time).
Modifications (iroh-native/fsr/patches/ffx-1.1.4-iroh.patch, 3 files): host/ffx_types.h makes FFX_SDK_DEFAULT_CONTEXT_SIZE
overridable (Linux wchar_t is 4 bytes); gpu/fsr3upscaler/ffx_fsr3upscaler_reproject.h and gpu/fsr1/ffx_fsr1.h carry the
FSR 3.1.5/3.1.6 shader fixes taken from AMD's own SDK 2.4.0 sources (also MIT-listed). Label: "FSR 3.1.4 host + 3.1.6 shaders".

Copyright (C) 2024 Advanced Micro Devices, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files(the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions :

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

(Licence text as in the SDK's LICENSE.txt, sha256 82cf74fc23885107c7f69249ac3f1dac55898ff68320eb2c753984cae6b2d78a.)
No AMD signed binary (FSR 4 DLLs) and no NVIDIA binary is bundled.
