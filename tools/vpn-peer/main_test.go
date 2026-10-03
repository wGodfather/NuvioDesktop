package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func TestPeerProcess(t *testing.T) {
	if os.Getenv("NUVIO_PEER_CHILD") == "1" {
		main()
		return
	}
}

// Real userspace WireGuard encryption on loopback, without a host VPN adapter.
func TestEncryptedIPv4IPv6AndDNS(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	child := exec.Command(exe, "-test.run=TestPeerProcess")
	child.Env = append(os.Environ(), "NUVIO_PEER_CHILD=1")
	child.Stdout = os.Stdout
	child.Stderr = os.Stderr
	if err = child.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { child.Process.Kill(); child.Wait() }()
	ready := false
	for i := 0; i < 50; i++ {
		response, err := http.Get("http://127.0.0.1:8765/ready")
		if err == nil {
			response.Body.Close()
			ready = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !ready {
		t.Fatal("Peer control unavailable")
	}
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	reply, err := http.Post("http://127.0.0.1:8765/enroll", "text/plain", strings.NewReader(base64.StdEncoding.EncodeToString(key.PublicKey().Bytes())))
	if err != nil {
		t.Fatal(err)
	}
	var enrolled struct {
		PublicKey string `json:"publicKey"`
		Port      int    `json:"port"`
	}
	err = json.NewDecoder(reply.Body).Decode(&enrolled)
	reply.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if enrolled.Port < 1 || enrolled.Port > 65535 {
		t.Fatal("Peer must report its actual UDP port")
	}
	pub, err := base64.StdEncoding.DecodeString(enrolled.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	tun, stack, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.90.0.2"), netip.MustParseAddr("fd90::2")}, []netip.Addr{netip.MustParseAddr("10.90.0.1")}, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	err = dev.IpcSet("private_key=" + hex.EncodeToString(key.Bytes()) + "\npublic_key=" + hex.EncodeToString(pub) + "\nendpoint=127.0.0.1:" + strconv.Itoa(enrolled.Port) + "\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\n")
	if err != nil {
		t.Fatal(err)
	}
	if err = dev.Up(); err != nil {
		t.Fatal(err)
	}
	client := http.Client{Transport: &http.Transport{DialContext: stack.DialContext}, Timeout: 10 * time.Second}
	for _, target := range []struct{ url, source string }{{"http://10.90.0.1:8765/probe", "10.90.0.2"}, {"http://[fd90::1]:8765/probe", "fd90::2"}, {"http://vpn-fixture.test:8765/probe", "10.90.0.2"}} {
		response, err := client.Get(target.url)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Source string `json:"source"`
		}
		if err = json.Unmarshal(body, &result); err != nil {
			t.Fatal(err)
		}
		if result.Source != target.source && !(strings.Contains(target.url, "vpn-fixture.test") && result.Source == "fd90::2") {
			t.Fatalf("Unexpected source: %s", result.Source)
		}
	}
	rejected, err := http.Post("http://127.0.0.1:8765/enroll", "text/plain", strings.NewReader("invalid"))
	if err != nil {
		t.Fatal(err)
	}
	rejected.Body.Close()
	if rejected.StatusCode != 409 {
		t.Fatal("Multiple peers must be rejected")
	}
}
