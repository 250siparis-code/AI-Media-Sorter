#!/usr/bin/env python3
"""
AI Media Sorter — Reusable Termux Manifest Executor for Large Media Collections
Reads file_operations.json and safely executes (or dry-runs) MOVE / COPY operations
with SHA-256 verification, collision protection, loop prevention, SAF detection,
and modification timestamp (mtime) preservation.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import time

def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def resolve_collision(dst_dir: str, filename: str) -> str:
    base, ext = os.path.splitext(filename)
    idx = 1
    while True:
        cand = os.path.join(dst_dir, f"{base}_{idx}{ext}")
        if not os.path.exists(cand):
            return cand
        idx += 1

def main():
    parser = argparse.ArgumentParser(description="Execute AI Media Sorter file_operations.json in Termux.")
    parser.add_argument("--manifest", default="file_operations.json", help="Path to file_operations.json")
    parser.add_argument("--execute", action="store_true", help="Disable dry-run and perform actual file operations")
    parser.add_argument("--yes", "-y", action="store_true", help="Skip interactive confirmation prompt")
    parser.add_argument("--mode", choices=["MOVE", "COPY"], default=None, help="Override operation mode")
    parser.add_argument("--collision", choices=["rename", "skip"], default="rename", help="Collision policy")
    args = parser.parse_args()

    if not os.path.exists(args.manifest):
        print(f"Error: Manifest not found: {args.manifest}", file=sys.stderr)
        sys.exit(1)

    with open(args.manifest, "r", encoding="utf-8") as f:
        payload = json.load(f)

    ops = payload.get("operations", [])
    dry_run = not args.execute

    if dry_run:
        print(f"[DRY-RUN MODE] Loaded {len(ops)} operations from {args.manifest}. Pass --execute to modify files.")
    else:
        if not args.yes:
            ans = input(f"Confirm execution of {len(ops)} media operations? Type 'YES' to proceed: ")
            if ans.strip() != "YES":
                print("Aborted by user.")
                sys.exit(0)

    seen_sources = set()
    stats = {"ok": 0, "saf": 0, "dup": 0, "collision_skip": 0, "mtime_warn": 0, "failed": 0}

    for rec in ops:
        op = args.mode or rec.get("requestedOperation", "MOVE")
        src = rec.get("resolvedPosixSourcePath") or rec.get("originalPathOrUri", "")
        dst_dir = rec.get("destinationDirectory", "")
        dst_name = rec.get("destinationFileName", "")
        mtime_sec = int(rec.get("originalModificationEpochSec", 0))
        cat = rec.get("effectiveCategory", "Other")
        score = rec.get("classificationScoreUncalibrated", 0.0)
        is_saf = bool(rec.get("requiresSafAccessOnly", False)) or src.startswith("content://")

        if is_saf:
            stats["saf"] += 1
            print(f"[SAF-ONLY] Cannot access Android SAF URI directly in Termux: {src}")
            continue

        if src in seen_sources:
            stats["dup"] += 1
            print(f"[SKIP-DUPLICATE] Already processed in batch: {src}")
            continue
        seen_sources.add(src)

        target_path = os.path.join(dst_dir, dst_name)
        if os.path.abspath(src) == os.path.abspath(target_path):
            stats["dup"] += 1
            continue

        if os.path.abspath(dst_dir).startswith(os.path.abspath(src) + os.sep):
            stats["failed"] += 1
            print(f"[ERROR-LOOP] Blocked moving directory into itself: {src} -> {dst_dir}")
            continue

        if dry_run:
            print(f"[DRY-RUN] {op}: '{src}' -> '{target_path}' (Cat: {cat}, Score: {score:.4f}, mtime: {mtime_sec})")
            stats["ok"] += 1
            continue

        if not os.path.isfile(src) or not os.access(src, os.R_OK):
            stats["failed"] += 1
            print(f"[ERROR-SOURCE] Unreadable or missing source: {src}")
            continue

        try:
            os.makedirs(dst_dir, exist_ok=True)
            src_hash = sha256_file(src)

            if os.path.exists(target_path):
                if args.collision == "skip":
                    stats["collision_skip"] += 1
                    print(f"[SKIP-COLLISION] Exists: {target_path}")
                    continue
                target_path = resolve_collision(dst_dir, dst_name)
                print(f"[COLLISION-RENAMED] -> {target_path}")

            tmp_target = os.path.join(dst_dir, f".aimediasorter.tmp.{os.getpid()}.{dst_name}")
            shutil.copyfile(src, tmp_target)
            dst_hash = sha256_file(tmp_target)
            if src_hash != dst_hash:
                os.remove(tmp_target)
                stats["failed"] += 1
                print(f"[ERROR-HASH] SHA-256 mismatch for {src}; source preserved.")
                continue

            os.replace(tmp_target, target_path)
            if mtime_sec > 0:
                try:
                    os.utime(target_path, (mtime_sec, mtime_sec))
                except OSError:
                    pass
                actual_mtime = int(os.stat(target_path).st_mtime)
                if abs(actual_mtime - mtime_sec) > 2:
                    stats["mtime_warn"] += 1
                    print(f"[WARN-TIMESTAMP] Filesystem restricted mtime on {target_path}")

            if op == "MOVE":
                os.remove(src)

            stats["ok"] += 1
            print(f"[OK-{op}] '{src}' -> '{target_path}' (SHA-256: {dst_hash[:16]}...)")
        except Exception as e:
            stats["failed"] += 1
            print(f"[ERROR] {src}: {e}")

    print("==============================================================================")
    print(f"Summary (DRY_RUN={dry_run}): {stats}")

if __name__ == "__main__":
    main()
