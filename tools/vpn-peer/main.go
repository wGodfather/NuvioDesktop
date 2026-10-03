// Disposable CI peer. Keys remain in memory; no host route/adapter is installed.
package main

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/anacrolix/torrent/mse"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

var enrollments atomic.Int32
var probes atomic.Int64
var dnsQueries atomic.Int64
var transfers atomic.Int64
var encryptedSeeds atomic.Int64

func main() {
	fixturePath := flag.String("fixture", "", "JSON from qa_torrent_seed.py with VPN advertised address")
	flag.Parse()
	fixture := map[string]any{}
	if *fixturePath != "" {
		data, err := os.ReadFile(*fixturePath)
		must(err)
		must(json.Unmarshal(data, &fixture))
	}
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	must(err)
	tun, stack, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.90.0.1"), netip.MustParseAddr("fd90::1")}, nil, 1420)
	must(err)
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	// Let the OS allocate an available port; Windows runners may reserve 51820.
	must(dev.IpcSet("private_key=" + hex.EncodeToString(key.Bytes()) + "\nlisten_port=0\n"))
	must(dev.Up())
	configuration, err := dev.IpcGet()
	must(err)
	port := 0
	for _, line := range strings.Split(configuration, "\n") {
		if value, found := strings.CutPrefix(line, "listen_port="); found {
			port, err = strconv.Atoi(value)
			must(err)
		}
	}
	// The UAPI response contains a private key: never log it.
	configuration = ""
	if port < 1 || port > 65535 {
		log.Fatal("Peer UDP port unavailable")
	}
	control := http.NewServeMux()
	control.HandleFunc("/enroll", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || enrollments.Load() >= 1 {
			http.Error(w, "Single CI peer only", 409)
			return
		}
		raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 128))
		if err != nil {
			http.Error(w, "Invalid key", 400)
			return
		}
		pub, err := base64.StdEncoding.DecodeString(string(raw))
		if err != nil || len(pub) != 32 {
			http.Error(w, "Invalid public key", 400)
			return
		}
		if err = dev.IpcSet("public_key=" + hex.EncodeToString(pub) + "\nallowed_ip=10.90.0.2/32\nallowed_ip=fd90::2/128\n"); err != nil {
			http.Error(w, "Peer configuration failed", 500)
			return
		}
		enrollments.Add(1)
		response := map[string]any{"publicKey": base64.StdEncoding.EncodeToString(key.PublicKey().Bytes()), "port": port, "fixture": fixture}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(response)
	})
	control.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "ready") })
	control.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]int64{"probes": probes.Load(), "dnsQueries": dnsQueries.Load(), "transfers": transfers.Load(), "encryptedSeeds": encryptedSeeds.Load()})
	})
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || (ip != "10.90.0.2" && ip != "fd90::2") {
			http.Error(w, "Unexpected VPN source", 403)
			return
		}
		probes.Add(1)
		json.NewEncoder(w).Encode(map[string]string{"source": ip})
	})
	for _, address := range []string{"10.90.0.1:8765", "[fd90::1]:8765"} {
		listener, err := stack.ListenTCPAddrPort(netip.MustParseAddrPort(address))
		must(err)
		go func() { must(http.Serve(listener, inner)) }()
	}
	for _, address := range []string{"10.90.0.1:53", "[fd90::1]:53"} {
		udp, err := stack.ListenUDPAddrPort(netip.MustParseAddrPort(address))
		must(err)
		go func() {
			buf := make([]byte, 4096)
			for {
				n, sender, err := udp.ReadFrom(buf)
				if err != nil {
					return
				}
				var query dnsmessage.Message
				if query.Unpack(buf[:n]) != nil || len(query.Questions) != 1 {
					continue
				}
				question := query.Questions[0]
				reply := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true, Authoritative: true}, Questions: query.Questions}
				if question.Name.String() != "vpn-fixture.test." {
					reply.RCode = dnsmessage.RCodeNameError
				} else {
					header := dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: dnsmessage.ClassINET, TTL: 0}
					switch question.Type {
					case dnsmessage.TypeA:
						reply.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AResource{A: [4]byte{10, 90, 0, 1}}}}
					case dnsmessage.TypeAAAA:
						reply.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AAAAResource{AAAA: netip.MustParseAddr("fd90::1").As16()}}}
					}
				}
				packed, err := reply.Pack()
				if err == nil {
					dnsQueries.Add(1)
					udp.WriteTo(packed, sender)
				}
			}
		}()
	}
	// Loopback seed/tracker are exposed exclusively inside the encrypted VPN stack.
	for _, field := range []string{"seed_port", "tracker_port"} {
		if value, ok := fixture[field].(float64); ok {
			port := int(value)
			if port < 1 || port > 65535 {
				log.Fatal("Invalid fixture port")
			}
			listener, err := stack.ListenTCPAddrPort(netip.AddrPortFrom(netip.MustParseAddr("10.90.0.1"), uint16(port)))
			must(err)
			go func() {
				for {
					incoming, err := listener.Accept()
					if err != nil {
						return
					}
					go func() {
						defer incoming.Close()
						var stream io.ReadWriter = incoming
						if field == "seed_port" {
							infoHash, err := hex.DecodeString(fmt.Sprint(fixture["info_hash"]))
							if err != nil || len(infoHash) != 20 {
								return
							}
							stream, err = receiveTorrentStream(incoming, infoHash)
							if err != nil {
								return
							}
						}
						outgoing, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 5*time.Second)
						if err != nil {
							return
						}
						defer outgoing.Close()
						transfers.Add(1)
						done := make(chan struct{})
						go func() { io.Copy(outgoing, stream); outgoing.Close(); close(done) }()
						io.Copy(stream, outgoing)
						incoming.Close()
						<-done
					}()
				}
			}()
		}
	}
	fmt.Println("Disposable WireGuard peer ready; keys are not logged.")
	// Emulator host alias forwards to loopback; Windows uses localhost for control.
	must(http.ListenAndServe("127.0.0.1:8765", control))
}

type bufferedStream struct {
	io.Reader
	io.Writer
}

// The Android engine accepts a plain BEP handshake; desktop TorrServer prefers
// MSE. Decode its transport using the upstream implementation before forwarding
// to the same bounded loopback fixture. WireGuard remains the outer encryption.
func receiveTorrentStream(conn net.Conn, infoHash []byte) (io.ReadWriter, error) {
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	defer conn.SetDeadline(time.Time{})
	header := make([]byte, 20)
	if _, err := io.ReadFull(conn, header); err != nil {
		return nil, err
	}
	stream := bufferedStream{io.MultiReader(bytes.NewReader(header), conn), conn}
	if bytes.Equal(header, []byte("\x13BitTorrent protocol")) {
		return stream, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	plain, _, err := mse.ReceiveHandshake(ctx, stream, func(yield func([]byte) bool) { yield(infoHash) }, mse.DefaultCryptoSelector)
	if err == nil {
		encryptedSeeds.Add(1)
	}
	return plain, err
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
