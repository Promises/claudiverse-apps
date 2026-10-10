#!/usr/bin/env python3
"""vendorcheck.py — prove openvpn/ is upstream ics-openvpn, except our own files.

    tools/vendorcheck.py               check what the next commit carries (the index)
    tools/vendorcheck.py --rev HEAD    check a commit instead
    tools/vendorcheck.py --upstream <dir>   read upstream from a local clone, not GitHub
    tools/vendorcheck.py --self-test   seed one changed and one unknown file; fail unless both are caught
    tools/vendorcheck.py --rev HEAD --list-identical > skip.txt
                                       the files proved upstream, as tree paths (openvpn/…), for
                                       leakgate's --skip-list: it skips exactly these and gates the rest

The module is vendored, not scrubbed: its licence notices name upstream authors
and must stay, so leakgate cannot pass on it. Instead every file in it must be
byte-identical to the upstream file at the commit recorded in
openvpn-vendor.json (line endings aside: git stores them as LF), or be one of
the files listed there as ours. Submodules are pinned by commit and not read.

Exit 0: everything accounted for. 1: a file differs from upstream or is not
upstream, unlisted; or a file listed as ours is missing or no longer differs
(the list must stay true). 2: usage or setup error.
"""
import argparse, json, os, subprocess, sys, tempfile

ROOT = subprocess.run(["git", "-C", os.path.dirname(os.path.abspath(__file__)), "rev-parse", "--show-toplevel"],
                      capture_output=True, text=True, check=True).stdout.strip()
SPEC = json.load(open(os.path.join(ROOT, "tools", "openvpn-vendor.json")))


def git(repo, *args, data=None):
    return subprocess.run(["git", "-C", repo, *args], input=data, capture_output=True, check=True).stdout


def module_files(rev):
    """{path in module: blob id} as the index (rev None) or a commit carries them; gitlinks skipped."""
    prefix = SPEC["module"] + "/"
    if rev is None:
        rows = git(ROOT, "ls-files", "-s", "-z", "--", prefix).split(b"\0")
        entries = [(r.split(b"\t")[0].split()[0], r.split(b"\t")[0].split()[1], r.split(b"\t", 1)[1]) for r in rows if r]
    else:
        rows = git(ROOT, "ls-tree", "-r", "-z", rev, "--", prefix).split(b"\0")
        entries = [(r.split(b"\t")[0].split()[0], r.split(b"\t")[0].split()[2], r.split(b"\t", 1)[1]) for r in rows if r]
    return {p.decode()[len(prefix):]: oid.decode() for mode, oid, p in entries if mode != b"160000"}


def read_blobs(repo, names):
    """Contents for each object name (a blob id or commit:path), None where it does not exist."""
    out = git(repo, "cat-file", "--batch", data=("\n".join(names) + "\n").encode())
    blobs, i = [], 0
    for _ in names:
        nl = out.index(b"\n", i)
        header = out[i:nl].split()
        if header[-1] == b"missing":
            blobs.append(None); i = nl + 1
        else:
            size = int(header[2]); blobs.append(out[nl + 1:nl + 1 + size]); i = nl + 1 + size + 1
    return blobs


def upstream_path(rel):
    for ours, theirs in SPEC["map"]:
        if ours.endswith("/") and rel.startswith(ours):
            return theirs + rel[len(ours):]
        if rel == ours:
            return theirs
    return None


def classify(files, upstream):
    """files: {path: bytes}. Returns {class: [paths]}."""
    paths = sorted(files)
    theirs = read_blobs(upstream, [f"{SPEC['commit']}:{upstream_path(p) or '/nonexistent'}" for p in paths])
    new, changed = SPEC["ours_new"], SPEC["ours_changed"]
    cls = {}
    for p, up in zip(paths, theirs):
        mine = files[p]
        if up is None:
            c = "ours-new" if p in new else "UNEXPECTED: not upstream and not listed as ours"
        elif up.replace(b"\r\n", b"\n") == mine.replace(b"\r\n", b"\n"):
            c = "LISTED AS OURS BUT IDENTICAL to upstream" if p in changed or p in new else "identical"
        else:
            c = "ours-changed" if p in changed else "UNEXPECTED: differs from upstream"
        cls.setdefault(c, []).append(p)
    for p in sorted(set(new) | set(changed)):
        if p not in files:
            cls.setdefault("LISTED AS OURS BUT MISSING", []).append(p)
    return cls


def failed(cls):
    return any(c.startswith(("UNEXPECTED", "LISTED")) for c in cls)


def upstream_repo(args, tmp):
    if args.upstream:
        return args.upstream
    repo = os.path.join(tmp, "upstream")
    git(tmp, "init", "-q", "--bare", repo)
    git(repo, "fetch", "-q", "--depth=1", SPEC["upstream"], SPEC["commit"])
    return repo


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rev", help="a commit to check instead of the index")
    ap.add_argument("--upstream", help="a local ics-openvpn clone that has the recorded commit")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list-identical", action="store_true",
                    help="print the files identical to upstream as tree paths, one a line, and nothing else; "
                         "exit 1 (printing nothing) unless the whole module passes")
    args = ap.parse_args()
    if args.upstream and not os.path.isdir(args.upstream):
        ap.error(f"not a directory: {args.upstream}")

    ids = module_files(args.rev)
    if not ids:
        print(f"vendorcheck: no files under {SPEC['module']}/ in {args.rev or 'the index'}", file=sys.stderr)
        return 2
    paths = sorted(ids)
    files = dict(zip(paths, read_blobs(ROOT, [ids[p] for p in paths])))

    with tempfile.TemporaryDirectory() as tmp:
        try:
            upstream = upstream_repo(args, tmp)
        except subprocess.CalledProcessError as e:
            print(f"vendorcheck: cannot get {SPEC['commit']} from {args.upstream or SPEC['upstream']}: "
                  f"{e.stderr.decode().strip()}", file=sys.stderr)
            return 2

        if args.self_test:
            victim = next(p for p in paths if p not in SPEC["ours_new"] and p not in SPEC["ours_changed"])
            seeded = dict(files)
            seeded[victim] = files[victim] + b"\n/* seeded */\n"
            seeded["src/main/res/values/seeded.xml"] = b"<resources/>\n"
            cls = classify(seeded, upstream)
            caught = victim in cls.get("UNEXPECTED: differs from upstream", []) and \
                "src/main/res/values/seeded.xml" in cls.get("UNEXPECTED: not upstream and not listed as ours", [])
            clean = not failed(classify(files, upstream))
            print("self-test:", "a seeded change and a seeded new file are both caught" if caught
                  else "a seeded fault was NOT caught", "| the real tree passes" if clean else "| the real tree FAILS")
            return 0 if caught and clean else 1

        cls = classify(files, upstream)

    if args.list_identical:
        # Only a passing module may hand out a skip list: on a failure the list would hide
        # whatever made it fail from the gate as well.
        if failed(cls):
            print(f"vendorcheck: {SPEC['module']}/ fails; no list. Run without --list-identical to see why.", file=sys.stderr)
            return 1
        for p in cls.get("identical", []):
            print(f"{SPEC['module']}/{p}")
        return 0

    for c in sorted(cls):
        print(f"{c}: {len(cls[c])}")
        if c != "identical":
            for p in cls[c]:
                print("   ", p)
    print(f"vendorcheck: {SPEC['module']}/ in {args.rev or 'the index'} against ics-openvpn {SPEC['commit'][:8]}: "
          + ("FAIL" if failed(cls) else "ok"), file=sys.stderr)
    return 1 if failed(cls) else 0


if __name__ == "__main__":
    sys.exit(main())
