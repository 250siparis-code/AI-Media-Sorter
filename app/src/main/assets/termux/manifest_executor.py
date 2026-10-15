#!/usr/bin/env python3
"""Offline, dry-run-by-default executor for AI Media Sorter manifests.

Copies are staged, read back and published without overwriting existing files.
MOVE additionally requires unchanged source identity/content and a verified final
destination. Modification time is checked separately from embedded metadata.
"""
import argparse
import base64
import errno
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path

BUFFER_SIZE = 65536
MTIME_TOLERANCE_NS = 2_000_000_000


def sha256_file(path):
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError("Source/destination must be a regular file")
        digest = hashlib.sha256()
        while True:
            chunk = stream.read(BUFFER_SIZE)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def identity(path):
    info = os.stat(path, follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("Symbolic links and non-regular files are not supported")
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def is_within(path, root):
    path = os.path.realpath(path)
    root = os.path.realpath(root)
    return os.path.commonpath((path, root)) == root


def validate_record(record):
    source = record.get("resolvedPosixSourcePath") or record.get("originalPathOrUri", "")
    directory = record.get("destinationDirectory", "")
    name = record.get("destinationFileName", "")
    if not source.startswith("/") or not directory.startswith("/"):
        raise ValueError("An absolute POSIX source and destination are required")
    if not name or name in (".", "..") or "/" in name or "\\" in name or "\0" in name:
        raise ValueError("Unsafe destination filename")
    if os.path.islink(source):
        raise ValueError("Symbolic-link sources are not supported")
    source_root = record.get("sourceRootDirectory") or str(Path(source).parent)
    destination_root = record.get("destinationRootDirectory") or directory
    if not is_within(source, source_root):
        raise ValueError("Source is outside the manifest source root")
    if not is_within(directory, destination_root):
        raise ValueError("Destination escapes the manifest destination root")
    if is_within(directory, source_root):
        raise ValueError("Destination must be outside the scanned source root")
    if os.path.realpath(source) == os.path.realpath(os.path.join(directory, name)):
        raise ValueError("Source and destination are identical")
    return source, directory, name


def candidate_name(name, counter):
    if counter == 0:
        return name
    base, extension = os.path.splitext(name)
    return base + "_" + str(counter) + extension


def publish_exclusive(staging, directory, name, collision):
    """Hard-link publication is atomic/no-clobber. Unsupported filesystems use O_EXCL."""
    counter = 0
    while True:
        destination = os.path.join(directory, candidate_name(name, counter))
        try:
            try:
                os.link(staging, destination, follow_symlinks=False)
            except OSError as error:
                if error.errno == errno.EEXIST:
                    raise FileExistsError(destination)
                if error.errno not in (errno.EPERM, errno.EACCES, errno.EXDEV,
                                      errno.ENOSYS, errno.EOPNOTSUPP, errno.ENOTSUP):
                    raise
                fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                             getattr(os, "O_NOFOLLOW", 0), 0o600)
                with os.fdopen(fd, "wb") as output:
                    with open(staging, "rb") as incoming:
                        shutil.copyfileobj(incoming, output, BUFFER_SIZE)
                    output.flush()
                    os.fsync(output.fileno())
            return destination
        except FileExistsError:
            if collision == "skip":
                return None
            counter += 1


def sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def execute_operation(record, mode=None, collision="rename", timestamp_policy=None):
    source, directory, name = validate_record(record)
    operation = mode or record.get("requestedOperation", "COPY")
    if operation not in ("COPY", "MOVE"):
        raise ValueError("Unsupported operation: " + str(operation))
    policy = timestamp_policy or record.get("timestampPolicy", "keep-original")
    if policy not in ("keep-original", "best-effort"):
        raise ValueError("Unsupported timestamp policy")
    before = identity(source)
    original_info = os.stat(source, follow_symlinks=False)
    expected_ms = int(record.get("originalModificationTimestampMs", 0))
    if expected_ms and abs(original_info.st_mtime_ns - expected_ms * 1_000_000) > MTIME_TOLERANCE_NS:
        raise ValueError("Source timestamp changed since analysis; re-analyze")
    source_hash = sha256_file(source)
    expected_hash = record.get("sourceSha256")
    if expected_hash and source_hash != expected_hash:
        raise ValueError("Source contents changed since analysis; re-analyze")
    os.makedirs(directory, exist_ok=True)
    # Re-check confinement after directory creation, including existing symlinks.
    validate_record(record)
    staging = None
    try:
        fd, staging = tempfile.mkstemp(prefix=".aimediasorter-", suffix=".part", dir=directory)
        with os.fdopen(fd, "wb") as output:
            source_fd = os.open(source, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(source_fd, "rb") as incoming:
                shutil.copyfileobj(incoming, output, BUFFER_SIZE)
            output.flush()
            os.fsync(output.fileno())
        if sha256_file(staging) != source_hash or identity(source) != before:
            raise ValueError("Staging verification failed; original preserved")
        destination = publish_exclusive(staging, directory, name, collision)
        if destination is None:
            return {"status": "SKIPPED_COLLISION", "source": source}
        if sha256_file(destination) != source_hash:
            raise ValueError("Final destination readback failed; original preserved; inspect " + destination)
        timestamp_preserved = False
        try:
            os.utime(destination, ns=(original_info.st_atime_ns, original_info.st_mtime_ns),
                     follow_symlinks=False)
            actual = os.stat(destination, follow_symlinks=False).st_mtime_ns
            timestamp_preserved = abs(actual - original_info.st_mtime_ns) <= MTIME_TOLERANCE_NS
        except OSError:
            pass
        publication_durable = True
        try:
            current = directory
            while True:
                sync_directory(current)
                parent = os.path.dirname(current)
                if parent == current:
                    break
                current = parent
        except OSError:
            publication_durable = False
        source_retained = True
        actual_operation = "COPY"
        if operation == "MOVE" and publication_durable and (timestamp_preserved or policy == "best-effort"):
            # A published file or a source can change independently of the staged copy.
            if identity(source) != before or sha256_file(source) != source_hash:
                raise ValueError("Source changed before deletion; source and verified copy preserved")
            if sha256_file(destination) != source_hash:
                raise ValueError("Destination changed before deletion; original preserved")
            if identity(source) != before:
                raise ValueError("Source identity changed before deletion; original preserved")
            os.unlink(source)
            if os.path.lexists(source):
                raise OSError("Source deletion could not be confirmed")
            source_retained = False
            actual_operation = "MOVE"
        return {
            "status": "COMPLETED" if timestamp_preserved else "COMPLETED_TIMESTAMP_WARNING",
            "source": source, "destination": destination, "actualOperation": actual_operation,
            "sourceRetained": source_retained, "sha256": source_hash,
            "publicationDurable": publication_durable,
            "timestampPreserved": timestamp_preserved,
            "originalModificationNs": original_info.st_mtime_ns,
            "destinationModificationNs": os.stat(destination, follow_symlinks=False).st_mtime_ns,
        }
    finally:
        if staging is not None and os.path.exists(staging):
            os.unlink(staging)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=None)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--yes", "-y", action="store_true")
    parser.add_argument("--mode", choices=("MOVE", "COPY"),
                        default=globals().get("EMBEDDED_METHOD"))
    parser.add_argument("--collision", choices=("rename", "skip"), default="rename")
    parser.add_argument("--timestamp-policy", choices=("keep-original", "best-effort"))
    parser.add_argument("--include-review", action="store_true")
    args = parser.parse_args(argv)
    if args.manifest:
        with open(args.manifest, encoding="utf-8") as incoming:
            payload = json.load(incoming)
    elif globals().get("EMBEDDED_MANIFEST_B64"):
        payload = json.loads(base64.b64decode(EMBEDDED_MANIFEST_B64).decode("utf-8"))
    else:
        with open("file_operations.json", encoding="utf-8") as incoming:
            payload = json.load(incoming)
    operations = payload.get("operations")
    if not isinstance(operations, list):
        raise ValueError("Manifest operations must be a list")
    if args.execute and not args.yes:
        if input("Confirm " + str(len(operations)) + " operations. Type YES: ").strip() != "YES":
            print("Aborted.")
            return 0
    dry_run = not args.execute
    print("[DRY-RUN]" if dry_run else "[EXECUTE]")
    stats = {"ok": 0, "saf": 0, "review": 0, "duplicate": 0, "skipped": 0, "failed": 0}
    seen = set()
    for record in operations:
        try:
            source = record.get("resolvedPosixSourcePath") or record.get("originalPathOrUri", "")
            if record.get("requiresSafAccessOnly") or source.startswith("content://"):
                stats["saf"] += 1
                print("[SAF-ONLY] " + source)
                continue
            if record.get("needsManualReview") and not record.get("isManuallyCorrected") and not args.include_review:
                stats["review"] += 1
                print("[SKIP-REVIEW] " + source)
                continue
            key = os.path.realpath(source)
            if key in seen:
                stats["duplicate"] += 1
                continue
            seen.add(key)
            src, directory, name = validate_record(record)
            if dry_run:
                print("[PLAN] " + (args.mode or record.get("requestedOperation", "COPY")) +
                      ": " + repr(src) + " -> " + repr(os.path.join(directory, name)))
                stats["ok"] += 1
                continue
            result = execute_operation(record, args.mode, args.collision, args.timestamp_policy)
            if result["status"] == "SKIPPED_COLLISION":
                stats["skipped"] += 1
            else:
                stats["ok"] += 1
            print(json.dumps(result, ensure_ascii=False))
        except (OSError, ValueError, TypeError) as error:
            stats["failed"] += 1
            print("[ERROR] " + str(error), file=sys.stderr)
    print("Summary (dry_run=" + str(dry_run) + "): " + json.dumps(stats))
    return 1 if stats["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
