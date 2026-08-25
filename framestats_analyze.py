#!/usr/bin/env python3
"""Parse dumpsys gfxinfo framestats and report per-phase CPU timing."""
import sys

def analyze(path):
    with open(path) as f:
        content = f.read()

    lines = content.split('\n')
    data_lines = []
    in_profile = False
    for line in lines:
        stripped = line.strip()
        if stripped == '---PROFILEDATA---':
            in_profile = not in_profile
            continue
        if in_profile and stripped and not stripped.startswith('---'):
            data_lines.append(stripped)

    if not data_lines:
        print("No framestats data found.")
        return

    header = data_lines[0].split(',')
    data_rows = [line.split(',') for line in data_lines[1:] if line]

    total = len(data_rows)
    janky = sum(1 for r in data_rows if r[0] == '1')

    phase_times = {
        'HandleInput': [],
        'Animation': [],
        'PerformTraversals': [],
        'Draw': [],
        'Sync': [],
        'Total': [],
    }

    for r in data_rows:
        flags = int(r[0])
        intended_vsync = int(r[2])
        frame_completed = int(r[17])
        total_dur = (frame_completed - intended_vsync) / 1_000_000

        # Standard 24-col framestats:
        # 0=Flags 2=IntendedVsync 5=HandleInputStart 6=AnimationStart
        # 7=PerformTraversalsStart 8=DrawStart 13=SyncQueued 14=SyncStart
        # 15=IssueDrawCommandsStart 16=SwapBuffers 17=FrameCompleted 20=GpuCompleted
        handle = (int(r[6]) - int(r[5])) / 1_000_000 if int(r[6]) > 0 and int(r[5]) > 0 else 0
        anim = (int(r[7]) - int(r[6])) / 1_000_000 if int(r[7]) > 0 and int(r[6]) > 0 else 0
        trav = (int(r[8]) - int(r[7])) / 1_000_000 if int(r[8]) > 0 and int(r[7]) > 0 else 0
        draw = (int(r[15]) - int(r[8])) / 1_000_000 if int(r[15]) > 0 and int(r[8]) > 0 else 0
        sync = (int(r[16]) - int(r[13])) / 1_000_000 if int(r[16]) > 0 and int(r[13]) > 0 else 0

        phase_times['HandleInput'].append(handle)
        phase_times['Animation'].append(anim)
        phase_times['PerformTraversals'].append(trav)
        phase_times['Draw'].append(draw)
        phase_times['Sync'].append(sync)
        phase_times['Total'].append(total_dur)

    def percentile(data, p):
        if not data:
            return 0
        sorted_data = sorted(data)
        k = (len(sorted_data) - 1) * p / 100
        f = int(k)
        c = min(f + 1, len(sorted_data) - 1)
        return sorted_data[f] + (k - f) * (sorted_data[c] - sorted_data[f])

    print(f"Total frames: {total}")
    print(f"Janky frames: {janky} ({janky/total*100:.1f}%)")
    print()

    for name in ['Total', 'HandleInput', 'Animation', 'PerformTraversals', 'Draw', 'Sync']:
        filtered = [t for t in phase_times[name] if t > 0]
        if not filtered:
            continue
        print(f"{name}: count={len(filtered)} 50th={percentile(filtered,50):.1f}ms "
              f"90th={percentile(filtered,90):.1f}ms 95th={percentile(filtered,95):.1f}ms "
              f"99th={percentile(filtered,99):.1f}ms max={max(filtered):.1f}ms")

    print("\n--- Top 12 worst frames (total) ---")
    tall = sorted(range(len(data_rows)), key=lambda i: phase_times['Total'][i], reverse=True)[:12]
    for idx in tall:
        print(f"  frame#{idx}: total={phase_times['Total'][idx]:.0f}ms "
              f"handle={phase_times['HandleInput'][idx]:.0f}ms anim={phase_times['Animation'][idx]:.0f}ms "
              f"trav={phase_times['PerformTraversals'][idx]:.0f}ms draw={phase_times['Draw'][idx]:.0f}ms "
              f"sync={phase_times['Sync'][idx]:.0f}ms")

if __name__ == '__main__':
    analyze(sys.argv[1] if len(sys.argv) > 1 else 'framestats2.log')
