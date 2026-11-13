#!/usr/bin/env python3
"""MV-02: exercise only the new AVD's official console HTTP handlers.

First forward adb emulator-5580:8088 to localhost:18088 on ADB server 5038.
The browser tool is unavailable in this session. URLs and methods here come
from the actual official console index served by that new emulator.
"""
import argparse
import json
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:18088"
CLIENT = "mv02-platform-spike"
SCENARIOS = {"parked": ("Park", 0), "idling": ("Drive", 0),
             "moving": ("Drive", 36), "reverse": ("Reverse", 0)}


def request(path, method="GET", **parameters):
    query = urllib.parse.urlencode(parameters)
    url = BASE + path + ("?" + query if query else "")
    req = urllib.request.Request(url, method=method,
                                 data=b"" if method != "GET" else None)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=SCENARIOS)
    parser.add_argument("--hold-seconds", type=int, default=15)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 1 <= args.hold_seconds <= 120:
        parser.error("hold-seconds must be between 1 and 120")
    stop = threading.Event()
    errors = []

    def heartbeat():
        while not stop.is_set():
            try:
                request("/browser/session", "POST", client=CLIENT)
            except Exception as failure:
                errors.append(type(failure).__name__)
            stop.wait(0.8)

    request("/browser/session", "POST", client=CLIENT)
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        gear, speed = SCENARIOS[args.scenario]
        # Match the official UI's stop-before-gear-change sequence.
        request("/vehicle/controls", "PUT", speed=0, accelerator=0, brake=0, steering=0)
        request("/vehicle/gear", "PUT", gear=gear)
        request("/vehicle/controls", "PUT", speed=speed, accelerator=0, brake=0, steering=0)
        state = request("/vehicle/state")
        actual = state.get("gear", {}).get("actual") or {}
        if actual.get("value") != gear or state.get("controls", {}).get("speed_kph") != speed:
            raise RuntimeError("Console did not confirm requested gear/speed")
        if args.output:
            args.output.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"scenario": args.scenario, "gear": actual.get("value"),
                          "speed_kph": state["controls"]["speed_kph"],
                          "bridge_ready": state["gear"]["bridge_ready"],
                          "hold_seconds": args.hold_seconds}), flush=True)
        if stop.wait(args.hold_seconds):
            raise RuntimeError("Scenario interrupted")
        if errors:
            raise RuntimeError("Console heartbeat failed: " + ",".join(errors))
    finally:
        stop.set()
        worker.join(timeout=6)
        request("/browser/session", "POST", client=CLIENT, closing=1)


if __name__ == "__main__":
    main()
