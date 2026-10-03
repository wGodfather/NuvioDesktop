"""Prepare verified experimental review assets. Never publishes or installs anything."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile

import os
ROOT = Path(__file__).resolve().parents[2]
SOURCE = os.environ["NUVIO_REVIEW_SOURCE"]
OUT = Path(os.environ["NUVIO_REVIEW_OUTPUT"])
OUT.mkdir(exist_ok=True)
GH = "gh"
REPO = "wGodfather/NuvioDesktop"

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

checking = sys.argv[1:2] == ["--check"]
evidence = []
for run in sys.argv[2:] if checking else sys.argv[1:]:
    result = json.loads(subprocess.check_output([GH, "run", "view", run, "-R", REPO,
        "--json", "headSha,conclusion,status,url,workflowName"]))
    if result["headSha"] != SOURCE:
        changed = subprocess.check_output(["git", "diff", "--name-only", SOURCE, result["headSha"]], cwd=ROOT).decode().splitlines()
        assert changed and all(name.startswith("tools/vpn-peer/") for name in changed), "Different application source"
        result["applicationSourceEquivalent"] = True
        result["validationOnlyChanges"] = changed
    assert result["status"] == "completed" and result["conclusion"] == "success", result
    if result["workflowName"] in ["Nuvio Türkiye Windows", "Nuvio Türkiye Android"]:
        assert result["headSha"] == SOURCE, "Both packages must come from the exact source"
    if result["workflowName"] == "Nuvio VPN Windows isolation tests":
        # pull_request jobs check out a synthetic merge, not necessarily headSha.
        artifacts = json.loads(subprocess.check_output([GH, "api",
            f"repos/{REPO}/actions/runs/{run}/artifacts"]))["artifacts"]
        commits = [item["name"].removeprefix("vpn-windows-peer-") for item in artifacts
            if item["name"].startswith("vpn-windows-peer-")]
        assert len(commits) == 1 and re.fullmatch("[a-f0-9]{40}", commits[0])
        result["testedCheckoutCommit"] = commits[0]
        comparison = json.loads(subprocess.check_output([GH, "api",
            f"repos/{REPO}/compare/{SOURCE}...{commits[0]}"]))
        changed = [item["filename"] for item in comparison["files"]]
        assert len(changed) < 300 and all(name.startswith("tools/vpn-peer/") for name in changed), "Synthetic merge changes application source"
        result["testedCheckoutApplicationEquivalent"] = True
    evidence.append(result)
assert len(evidence) == 4, "Windows peer, Android matrix and both packages required"
assert {entry["workflowName"] for entry in evidence} == {
    "Nuvio Türkiye Windows", "Nuvio Türkiye Android", "Nuvio VPN Windows isolation tests",
    "Nuvio VPN Android and TV validation"}, "Four different acceptance workflows required"
if checking:
    print("PASS completed successful CI and exact package source, application-equivalent test-only changes")
    sys.exit(0)

android = OUT / "android"
windows = OUT / "windows"
inventory = json.loads((android / "native-library-inventory.json").read_text())
apks = sorted(android.glob("*.apk"))
assert len(apks) == 4 and len(inventory) == 4
assert {apk.name for apk in apks} == {
    f"Nuvio-Android-{abi}-0.1.30-alpha.apk" for abi in ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]}
assert set(inventory) == {apk.name for apk in apks}
signers = set()
for apk in apks:
    expected = (apk.with_suffix(apk.suffix + ".sha256")).read_text().split()[0]
    assert sha(apk) == expected == inventory[apk.name]["sha256"]
    signature = (apk.with_suffix(apk.suffix + ".signature.txt")).read_text()
    digest = next(line.split(": ", 1)[1] for line in signature.splitlines()
        if line.startswith("Signer #1 certificate SHA-256 digest:"))
    signers.add(digest)
    with zipfile.ZipFile(apk) as archive:
        for item in inventory[apk.name]["native_libraries"]:
            data = archive.read(item["path"])
            assert hashlib.sha256(data).hexdigest() == item["sha256"]
            assert data[:4] == b"\x7fELF" and data[5] == 1
            if data[4] == 2:
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                aligns = [struct.unpack_from("<Q", data, offset + i * size + 48)[0]
                    for i in range(count) if struct.unpack_from("<I", data, offset + i * size)[0] == 1]
                assert aligns and min(aligns) >= 16384
        for name in ["WireGuard-Android-APACHE-2.0.txt", "WireGuard-Go-MIT.txt", "Go-BSD-3-Clause.txt"]:
            assert "assets/vpn-licenses/" + name in archive.namelist()
assert len(signers) == 1
msis = list(windows.glob("*.msi"))
assert len(msis) == 1
assert sha(msis[0]) == msis[0].with_suffix(".msi.sha256").read_text().strip()
version = json.loads((OUT / "windows-package-info.json").read_text(encoding="utf-8-sig"))
assert version["version"] == "1.1.31" and version["signatureStatus"] == "NotSigned"

for name in ["VPN_VALIDATION_TR.md", "VPN_IMPLEMENTATION_TR.md", "RELEASE_NOTES_TR.md"]:
    shutil.copyfile(ROOT / name, OUT / name)
lic = OUT / "VPN-LICENSES.zip"
with zipfile.ZipFile(lic, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in (ROOT / "composeApp/src/androidMain/assets/vpn-licenses").iterdir():
        archive.write(path, "android/" + path.name)
    for path in (ROOT / "composeApp/src/desktopMain/native/vpn").glob("WireGuard-*.txt"):
        archive.write(path, "windows/" + path.name)

assets = sorted([*android.iterdir(), *windows.iterdir(),
    *(OUT / name for name in ["VPN_VALIDATION_TR.md", "VPN_IMPLEMENTATION_TR.md", "RELEASE_NOTES_TR.md"]),
    lic, OUT / "windows-package-info.json", OUT / "Windows-VPN-runtime.sha256"])
manifest = {"repository": REPO, "packageSourceCommit": SOURCE,
    "documentationCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
    "versionName": "0.1.30-alpha", "versionCode": 31, "msiVersion": "1.1.31",
    "experimental": True, "stableAccepted": False,
    "androidSignerSha256": next(iter(signers)), "windowsTrustedSigned": False,
    "ci": evidence,
    "inventoryScope": "Package hashes, APK native libraries and Windows VPN runtime; not a complete application SBOM",
    "files": [{"name": path.name, "bytes": path.stat().st_size, "sha256": sha(path)} for path in assets]}
(OUT / "VPN-REVIEW-MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
assets.append(OUT / "VPN-REVIEW-MANIFEST.json")
(OUT / "SHA256SUMS.txt").write_text("".join(sha(path) + "  " + path.name + "\n" for path in assets), encoding="ascii")
print("PASS same package source/application-equivalent CI, 4 signed APK hashes/native alignment/licenses, MSI hash/version/unsigned identity; review assets prepared")
