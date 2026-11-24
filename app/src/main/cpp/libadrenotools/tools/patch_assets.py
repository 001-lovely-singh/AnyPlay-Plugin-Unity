#!/usr/bin/env python3
"""
Rewrites the app package name inside the Winlator .tzst assets (rootfs, box64, drivers, ...).

Why: the prebuilt Linux files were built with the path /data/data/com.winlator/files/rootfs baked in
(ELF interpreter of box64, ld.so.cache, library search paths, ...). When the app has another package
name, those files cannot start. The replacement MUST have exactly the same length as the old name,
so every file keeps its size and layout.

Usage: patch_assets.py <assets_dir> <old_package> <new_package>
Needs the `zstd` command line tool.
"""
import io
import os
import subprocess
import sys
import tarfile


def rewrite_tar(src, dst, old, new):
    """Copies the tar stream `src` to `dst`, replacing `old` by `new` in file contents and link targets.
    Returns the number of changed (files, links)."""
    old_b, new_b = old.encode(), new.encode()
    files = links = 0
    with tarfile.open(fileobj=src, mode="r|") as tin, \
            tarfile.open(fileobj=dst, mode="w|", format=tarfile.GNU_FORMAT) as tout:
        for member in tin:
            if member.isreg():
                data = tin.extractfile(member).read()
                if old_b in data:
                    data = data.replace(old_b, new_b)
                    files += 1
                member.size = len(data)
                tout.addfile(member, io.BytesIO(data))
            else:
                if (member.issym() or member.islnk()) and old in member.linkname:
                    member.linkname = member.linkname.replace(old, new)
                    links += 1
                tout.addfile(member)
    return files, links


def contains(path, old):
    old_b = old.encode()
    proc = subprocess.Popen(["zstd", "-dc", path], stdout=subprocess.PIPE)
    found = False
    with tarfile.open(fileobj=proc.stdout, mode="r|") as tin:
        for member in tin:
            if (member.issym() or member.islnk()) and old in member.linkname:
                found = True
                break
            if member.isreg() and old_b in tin.extractfile(member).read():
                found = True
                break
    proc.stdout.close()
    proc.kill()
    proc.wait()
    return found


def patch_file(path, old, new):
    tmp = path + ".new"
    dec = subprocess.Popen(["zstd", "-dc", path], stdout=subprocess.PIPE)
    enc = subprocess.Popen(["zstd", "-q", "-f", "-T0", "-12", "-o", tmp], stdin=subprocess.PIPE)
    files, links = rewrite_tar(dec.stdout, enc.stdin, old, new)
    enc.stdin.close()
    if enc.wait() != 0 or dec.wait() != 0:
        raise SystemExit("zstd failed for " + path)
    os.replace(tmp, path)
    return files, links


def main():
    assets, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
    if len(old) != len(new):
        raise SystemExit("old and new package names must have the same length (%d vs %d)" % (len(old), len(new)))
    total = 0
    for root, _dirs, names in os.walk(assets):
        for name in sorted(names):
            if not name.endswith(".tzst"):
                continue
            path = os.path.join(root, name)
            if not contains(path, old):
                print("  skip   ", os.path.relpath(path, assets))
                continue
            files, links = patch_file(path, old, new)
            total += files + links
            print("  patched", os.path.relpath(path, assets), "(%d files, %d links)" % (files, links))
    print("done, %d entries changed" % total)


if __name__ == "__main__":
    main()
