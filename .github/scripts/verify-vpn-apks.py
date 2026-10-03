"""Read-only APK native inventory and 64-bit 16 KB ELF alignment gate."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

inventory = {}
for apk in sorted(Path(sys.argv[1]).glob("*.apk")):
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for license_name in ("WireGuard-Android-APACHE-2.0.txt", "WireGuard-Go-MIT.txt", "Go-BSD-3-Clause.txt"):
            assert any(name.endswith("vpn-licenses/" + license_name) for name in names), f"Missing {license_name}"
        for name in names:
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            data = archive.read(name)
            assert data[:4] == b"\x7fELF" and data[5] == 1, f"Unexpected ELF: {name}"
            bits = data[4]
            phoff = struct.unpack_from("<Q" if bits == 2 else "<I", data, 32 if bits == 2 else 28)[0]
            phsize, phcount = struct.unpack_from("<HH", data, 54 if bits == 2 else 42)
            aligns = [struct.unpack_from("<Q" if bits == 2 else "<I", data, phoff + i * phsize + (48 if bits == 2 else 28))[0]
                      for i in range(phcount) if struct.unpack_from("<I", data, phoff + i * phsize)[0] == 1]
            if bits == 2:
                assert aligns and min(aligns) >= 16384, f"64-bit library is not 16 KB aligned: {name}: {aligns}"
            libraries.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(), "load_alignments": aligns})
        assert any(item["path"].endswith("/libwg-go.so") for item in libraries), "WireGuard native backend missing"
    inventory[apk.name] = {"sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "native_libraries": libraries}
assert inventory, "No APKs to verify"
Path(sys.argv[1], "native-library-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
print(f"PASS native inventory, VPN licenses and 64-bit 16 KB ELF alignment: {len(inventory)} APKs")
