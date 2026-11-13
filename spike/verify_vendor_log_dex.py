#!/usr/bin/env python3
"""Check built DEX instructions, not just source rules or DEX string tables."""
import argparse
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexdump", type=Path, required=True)
    args = parser.parse_args()
    vendor_invokes = []
    result_callback_invokes = 0
    inlined_android_log_invokes = []
    sdk_classes = set()
    dex_count = 0
    with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory(prefix="mv02-dex-") as temporary:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            dex_count += 1
            path = Path(temporary) / name
            path.write_bytes(archive.read(name))
            process = subprocess.Popen([str(args.dexdump), "-d", str(path)],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       text=True, errors="replace")
            current_class = ""
            for line in process.stdout:
                if "Class descriptor" in line:
                    current_class = line.strip()
                    if "Lai/pleos/playground/stt/" in line:
                        sdk_classes.add(current_class)
                if "invoke-" in line:
                    if "ai/pleos/playground/stt/utils/Log;." in line:
                        vendor_invokes.append({"dex": name, "instruction": line.strip()})
                    if "ai/pleos/playground/stt/listener/ResultListener;.onUpdated:" in line:
                        result_callback_invokes += 1
                    if ("Lai/pleos/playground/stt/" in current_class
                            and "Lai/pleos/playground/stt/utils/Log;'" not in current_class
                            and "Landroid/util/Log;." in line):
                        inlined_android_log_invokes.append(current_class)
            error = process.stderr.read()
            if process.wait() != 0:
                raise RuntimeError("dexdump failed: " + error[:160])
    evidence = {"dex_count": dex_count, "sdk_class_count": len(sdk_classes),
                "vendor_log_invokes": len(vendor_invokes),
                "android_log_invokes_in_sdk_callers": len(inlined_android_log_invokes),
                "result_callback_invokes": result_callback_invokes}
    print(json.dumps(evidence, indent=2))
    if (dex_count == 0 or len(sdk_classes) < 10 or vendor_invokes
            or inlined_android_log_invokes or result_callback_invokes == 0):
        raise SystemExit("FAIL: SDK log removal or recognition callback preservation not demonstrated")


if __name__ == "__main__":
    main()
