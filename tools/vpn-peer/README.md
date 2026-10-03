# Disposable WireGuard CI peer

This test tool uses the official wireguard-go device and userspace netstack,
pinned to `ecfc5a8d54462e18e13c72173e2623d16d8e25a0` with Go module checksums.
It installs no host VPN adapter, routes or firewall. Product packages do not
contain this tool. The official module is MIT licensed; its source and license:
https://git.zx2c4.com/wireguard-go/ . Dependencies and integrity are in go.mod/go.sum.

The private X25519 key is generated in memory for each process. The loopback
control endpoint accepts one client public key and returns the server public
key. No private keys or UAPI configurations are logged. The encrypted network
contains only synthetic DNS, IP probe endpoints and optional proxies to the
repository's tiny generated torrent fixture. It does not forward public Internet
traffic and cannot substitute for physical device or provider speed acceptance.

Run `go test -v .` here to check real encryption, IPv4, IPv6 and DNS on loopback.
For Android CI, run the repository seed with `NUVIO_QA_ADVERTISE_IP=10.90.0.1`
and supply its fixture JSON with `-fixture`. Only disposable runners grant the
VPN app-op; product users grant consent through the system dialog.
