#!/usr/bin/env bash
# H-1: (re)build tools/hw-profiles/*.json from the Khronos Vulkan-Profiles repo checkout (third_party/Vulkan-Profiles, tag vulkan-sdk-1.4.363.0)
# and make each profile a SUBSET of what lavapipe can honour: the Profiles layer simulates but cannot add capabilities, and it still reports
# a profile feature the device lacks as enabled (vkCreateDevice then fails with -8 FEATURE_NOT_PRESENT on lavapipe, which says nothing about Iroh).
# Steps per profile: (1) copy, (2) drop VkPhysicalDeviceHostImageCopyProperties* (numeric enum values in pCopySrcLayouts crash the layer's JSON
# loader: Json::LogicError asCString), (3) set to false every feature the layer warns "enabled in the profile, but the device does not support it",
# (4) intersect format feature bits with lavapipe (tools/hw-fmt-intersect.py + hw-fmt-probe.c).
set -euo pipefail
cd "$(dirname "$0")"; H=$PWD/hw-profiles; R=$PWD/../third_party/Vulkan-Profiles/profiles/LunarG; mkdir -p "$H"
L=$HOME/.local/share/vulkan/explicit_layer.d; [ -f $L/VkLayer_khronos_profiles.json ] || { echo "layer missing"; exit 2; }
cp_p() { cp "$(find $R -name "$1" | head -1)" "$H/$2.json"; }
cp_p vp_gpuinfo_nvidia_geforce_rtx_2060_537_59_0_0_windows_11.json nv-turing-rtx2060   # from desktop_max_2026 (two dirs hold it; the first find hit is fine, same content class)
cp_p vp_gpuinfo_nvidia_geforce_rtx_4070_551_61_0_0_windows_11.json nv-ada-rtx4070
cp_p vp_gpuinfo_nvidia_geforce_rtx_5080_610_62_0_0_windows_11.json nv-blackwell-rtx5080
cp_p vp_gpuinfo_nvidia_geforce_gtx_1650_610_62_0_0_windows_10.json nv-gtx1650-no-rt
cp_p vp_gpuinfo_nvidia_geforce_gtx_1060_537_13_0_0_windows_10.json nv-pascal-gtx1060
cp_p vp_gpuinfo_amd_radeon_rx_5700_xt__radv_navi10__26_0_99_arch_unknown.json amd-rdna1-rx5700xt-radv
cp_p vp_gpuinfo_amd_radeon_rx_6600__radv_navi23__23_2_99_endeavouros_unknown.json amd-rdna2-rx6600-radv
cp_p vp_gpuinfo_amd_radeon_rx_7800_xt__radv_navi32__24_0_99_arch_unknown.json amd-rdna3-rx7800xt-radv
cp_p vp_gpuinfo_amd_radeon_rx_9070_xt__radv_gfx1201__26_1_99_cachyos_unknown.json amd-rdna4-rx9070xt-radv
cp_p vp_gpuinfo_amd_radeon_rx_580_series__radv_polaris10__23_1_7_arch_unknown.json amd-polaris-rx580-radv
cp_p vp_gpuinfo_intel_r__arc_tm__a770_graphics__dg2__26_1_2_arch_unknown.json intel-arc-a770-anv
cp_p vp_gpuinfo_intel_r__arc_tm__a380_graphics__dg2__26_1_3_opensuse_tumbleweed_20260618.json intel-arc-a380-anv
cp_p vp_gpuinfo_intel_r__arc_tm__b580_graphics_0_406_632_windows_10.json intel-arc-b580-win
cp_p vp_gpuinfo_intel_r__iris_r__xe_graphics__tgl_gt2__26_1_3_void_unknown.json intel-xe-tgl-igpu-anv
cp_p vp_gpuinfo_intel_r__uhd_graphics_620__whl_gt2__23_1_7_arch_unknown.json intel-uhd620-gen9-anv
cp_p vp_gpuinfo_intel_r__hd_graphics_520__skl_gt2__21_1_1_arch_unknown.json intel-hd520-skl-vk12-lowend
# hand-made Ampere-class: the RTX 4070 (Ada) profile minus the Ada-only extensions (opacity micromap, shader execution reordering)
jq 'del(.capabilities.device.extensions.VK_EXT_opacity_micromap, .capabilities.device.extensions.VK_NV_ray_tracing_invocation_reorder, .capabilities.device.features.VkPhysicalDeviceOpacityMicromapFeaturesEXT, .capabilities.device.properties.VkPhysicalDeviceOpacityMicromapPropertiesEXT, .capabilities.device.features.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV, .capabilities.device.properties.VkPhysicalDeviceRayTracingInvocationReorderPropertiesNV) | (.profiles|=with_entries(.key|="VP_HAND_NVIDIA_Ampere_from_RTX4070"))' "$H/nv-ada-rtx4070.json" > "$H/nv-ampere-hand.json"
T=$(mktemp -d)
PROBE=$T/fmtprobe; gcc "$PWD/hw-fmt-probe.c" -o "$PROBE" -lvulkan
for f in "$H"/*.json; do
  p=$(basename "$f" .json)
  jq 'del(.capabilities.device.properties.VkPhysicalDeviceHostImageCopyPropertiesEXT, .capabilities.device.properties.VkPhysicalDeviceHostImageCopyProperties)' "$f" > $T/a.json && mv $T/a.json "$f"
  python3 -I "$PWD/hw-feature-sanitize.py" "$f"
  # (5) gpuinfo format captures omit extension-only format bits: a profile with VK_KHR_acceleration_structure gets the AS-vertex-buffer bit on the six formats the spec requires it for
  jq 'if (.capabilities.device.extensions|has("VK_KHR_acceleration_structure")) then reduce ("VK_FORMAT_R32G32_SFLOAT","VK_FORMAT_R32G32B32_SFLOAT","VK_FORMAT_R16G16_SFLOAT","VK_FORMAT_R16G16B16A16_SFLOAT","VK_FORMAT_R16G16_SNORM","VK_FORMAT_R16G16B16A16_SNORM") as $k (.; .capabilities.device.formats[$k].VkFormatProperties.bufferFeatures |= ((. // []) + ["VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR"] | unique)) else . end' "$f" > $T/a.json && mv $T/a.json "$f"
  python3 -I "$PWD/hw-fmt-intersect.py" "$f" "$PROBE" >/dev/null   # (4) format features := profile ∩ lavapipe (the layer cannot simulate extra bits: lavapipe lacks BLIT_DST on B10G11R11 and the over-claim made validation flag vkCmdBlitImage)
done
rm -rf $T
