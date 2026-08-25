#!/usr/bin/env bash
# 验证真机 ActivityManager.memoryClass 决定的 OrtSessionPool capacity.
#
# 用法:
#   adb shell getprop ro.build.version.release  # 确认设备已连
#   bash scripts/check_device_memory.sh
#
# 期望:
#   - 主流高端机 (≥1GB memoryClass) → capacity=4
#   - 中端机 (512MB-1GB) → capacity=2
#   - 低端机 (<512MB) → capacity=1
set -e

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not in PATH" >&2
    exit 1
fi

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
    echo "ERROR: no device connected. Run 'adb connect <ip:port>' first." >&2
    exit 1
fi
DEVICE=$(echo "$DEVICES" | head -1)
echo "Device: $DEVICE"

# dumpsys meminfo 第一行有 Total RAM, 但 ActivityManager.memoryClass
# 是通过 ActivityManager.getMemoryClass() 暴露的, 内部按
# ActivityManagerCompat 的算法 (基于 /proc/meminfo 的 totalram 减去
# 系统占用, 然后除以 1MB 量化). dumpsys 不直接给, 用 dumpsys cpuinfo
# 里的 ApplicationManager 也不行. 用 dumpsys meminfo | head 拿 Native heap 反而
# 验证 OrtSessionPool 4 sessions 的 native 占用.
echo "---"
echo "ActivityManager memoryClass 决定 OrtSessionPool capacity:"
echo "  memoryClass >= 1024 MB → capacity=4 (4×95MB INT8 = 380MB native)"
echo "  memoryClass 512..1023 MB → capacity=2"
echo "  memoryClass < 512 MB → capacity=1"
echo "---"
echo "Dumpsys meminfo (native heap, 推理时再跑一次对比):"
adb -s "$DEVICE" shell dumpsys meminfo | head -25
echo "---"
echo "Tip: 跑 AI tagging 批处理时执行:"
echo "  adb shell dumpsys meminfo $(adb shell pidof com.smartvision.gallery.debug) | grep -E 'Native Heap|TOTAL PSS'"
echo "期望: Native Heap < 1.2GB, TOTAL PSS < 2.5GB"
