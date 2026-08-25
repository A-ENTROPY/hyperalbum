#!/usr/bin/env python3
"""CMake wrapper: forwards all args to the real cmake via subprocess.
This works around a Windows issue where Java/Gradle spawning cmake causes STATUS_STACK_BUFFER_OVERFLOW."""
import subprocess, sys, os

real_cmake = r"G:\opencode\tools\android-sdk\cmake\3.30.3\bin\cmake.exe"
os.environ.setdefault("ANDROID_NDK_HOME", r"G:\opencode\tools\android-sdk\ndk\27.0.12077973")
os.environ.setdefault("ANDROID_SDK_ROOT", r"G:\opencode\tools\android-sdk")
os.environ.setdefault("ANDROID_HOME", r"G:\opencode\tools\android-sdk")

# Forward environment variables from parent process
result = subprocess.run([real_cmake] + sys.argv[1:], capture_output=True)
sys.stdout.buffer.write(result.stdout)
sys.stderr.buffer.write(result.stderr)
sys.exit(result.returncode)
