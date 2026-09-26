#!/usr/bin/env python3
"""Summarise iOS background-transfer probe logs (Phase 9, experiments 10a / 10b).

    python scripts/ios-transfer-report.py <folder-or-files...>

Reads the Debug build's `Documents/nuvio_diagnostics/downloads-*.jsonl` (export the whole folder
from Files) and prints, per build variant:

- the variant (`session_config` experiment, window, per-host limit);
- true concurrency: how many transfers had a response under way at once, from each task's
  `metrics` line (`responseStart`..`responseEnd`) - these are recorded by the system, so they cover
  the time the phone was locked and Nuvio was suspended;
- lock periods, and for each: transfers that *started* while locked (the queue moving on without an
  unlock) and transfers that finished while locked;
- outcomes (complete / short / overrun / failures / system cancels), retries, throughput;
- how many distinct hosts the transfers ended on (the per-host limit's reach).

Only the probe's own fields are read; the logs never contain URLs or headers.
"""
import collections
import glob
import json
import os
import sys


def load(paths):
    events = []
    for path in paths:
        files = sorted(glob.glob(os.path.join(path, "downloads-*.jsonl"))) if os.path.isdir(path) else [path]
        for name in files:
            with open(name, encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        events.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    events.sort(key=lambda e: e.get("t", 0))
    return events


def fmt_time(ms):
    import datetime
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


def intervals_overlap_profile(intervals):
    """Max concurrency, and seconds spent at each level (only while >= 1)."""
    points = []
    for start, end in intervals:
        points.append((start, 1))
        points.append((end, -1))
    points.sort(key=lambda p: (p[0], p[1]))
    level, peak, last = 0, 0, None
    time_at = collections.Counter()
    for t, delta in points:
        if last is not None and level > 0:
            time_at[level] += (t - last) / 1000
        level += delta
        peak = max(peak, level)
        last = t
    return peak, time_at


def locked_periods(events):
    periods, locked_at = [], None
    for e in events:
        if e.get("event") == "device_locked" and locked_at is None:
            locked_at = e["t"]
        elif e.get("event") == "device_unlocked" and locked_at is not None:
            periods.append((locked_at, e["t"]))
            locked_at = None
    if locked_at is not None:
        periods.append((locked_at, events[-1]["t"]))
    return periods


def report(events):
    configs = [e for e in events if e.get("event") == "session_config"]
    variants = sorted({c.get("experiment", "?") for c in configs}) or ["(no session_config: pre-.57 build)"]
    print("Variant(s):", ", ".join(variants))
    for c in configs[:1]:
        print(f"  window={c.get('window')} maxPerHost={c.get('maxPerHost')}")

    metrics = [e for e in events if e.get("event") == "metrics" and e.get("responseStart") and e.get("responseEnd")]
    intervals = [(m["responseStart"], m["responseEnd"]) for m in metrics]
    if intervals:
        peak, time_at = intervals_overlap_profile(intervals)
        busy = sum(time_at.values())
        avg = sum(level * secs for level, secs in time_at.items()) / busy if busy else 0
        print(f"\nTransfers measured (metrics): {len(intervals)}")
        print(f"  peak concurrent responses: {peak}")
        print(f"  average while any active: {avg:.2f}")
        print("  time at each level: " + ", ".join(f"{lvl}={secs:.0f}s" for lvl, secs in sorted(time_at.items())))
        span = (max(e for _, e in intervals) - min(s for s, _ in intervals)) / 1000
        print(f"  first response to last end: {span / 60:.1f} min")
        # `metrics` times are the task's LAST transaction. A task interrupted and range-resumed
        # (more than redirect + transfer) transferred earlier than its interval shows - every figure
        # above, and "started while locked" below, under-counts it. 58's t4/t5 were such tasks.
        partial = [m for m in metrics if (m.get("transactions") or 0) > 2]
        if partial:
            print(f"  WARNING: {len(partial)} task(s) range-resumed ({', '.join(m['id'] for m in partial)}); their "
                  "earlier transfer is missing from these intervals - read their first_progress/transfer_progress lines")
    else:
        print("\nNo metrics lines with response times (did any transfer finish?)")

    snapshots = [e for e in events if e.get("event") == "concurrency"]
    if snapshots:
        print(f"\nIn-app snapshots: {len(snapshots)}; max running={max(s.get('running', 0) for s in snapshots)}, "
              f"max suspended={max(s.get('suspended', 0) for s in snapshots)}, "
              f"max moving={max(s.get('moving', 0) for s in snapshots)}")

    finals = collections.Counter(e.get("outcome") for e in events if e.get("event") == "finalize")
    completes = [e for e in events if e.get("event") == "complete"]
    errors = collections.Counter(e.get("error") for e in completes if e.get("error") is not None and not e.get("ours"))
    system_cancels = sum(1 for e in completes if e.get("systemCancel") is not None)
    print("\nOutcomes:", dict(finals) or "none")
    print("Task errors (not ours):", dict(errors) or "none", f"| system cancels: {system_cancels}")
    bytes_done = sum(e.get("bytes", 0) or 0 for e in events if e.get("event") == "finalize" and e.get("outcome") == "complete")
    if intervals and bytes_done:
        span_s = (max(e for _, e in intervals) - min(s for s, _ in intervals)) / 1000
        print(f"Completed bytes: {bytes_done / 1e9:.2f} GB, overall {bytes_done / 1e6 / max(span_s, 1):.1f} MB/s")

    hosts = collections.Counter(m.get("hostTag") for m in metrics if m.get("hostTag"))
    if hosts:
        print(f"Distinct final hosts: {len(hosts)} ({', '.join(f'{h}x{n}' for h, n in hosts.most_common(6))})")

    periods = locked_periods(events)
    print(f"\nLocked periods: {len(periods)}")
    for start, end in periods:
        started = [m for m in metrics if start <= m["responseStart"] <= end]
        finished = [m for m in metrics if start <= m["responseEnd"] <= end]
        wakes = sum(1 for e in events if e.get("event") == "wake" and start <= e["t"] <= end)
        print(f"  {fmt_time(start)}-{fmt_time(end)} ({(end - start) / 60000:.0f} min): "
              f"{len(started)} transfers started while locked, {len(finished)} finished, {wakes} wakes")
        if intervals:
            inside = [(max(s, start), min(e, end)) for s, e in intervals if s < end and e > start]
            if inside:
                peak, _ = intervals_overlap_profile(inside)
                print(f"    peak concurrent while locked: {peak}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    events = load(sys.argv[1:])
    if not events:
        print("No probe events found.")
        sys.exit(1)
    report(events)


if __name__ == "__main__":
    main()
