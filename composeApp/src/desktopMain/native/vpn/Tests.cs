// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace NuvioVpn {
    static class NativeTests {
        static int count;
        internal static int FirewallSmokeTest() {
            // This intentionally changes machine-wide networking. Never run it on a user's computer.
            if (Environment.GetEnvironmentVariable("GITHUB_ACTIONS") != "true" || Environment.GetEnvironmentVariable("RUNNER_OS") != "Windows")
                throw new VpnError("ISOLATED_CI_REQUIRED");
            IPAddress target = Dns.GetHostAddresses("github.com").First(x => x.AddressFamily == AddressFamily.InterNetwork);
            Assert(CanConnect(target, 443), "baseline external connection");
            string binary = Path.Combine(Path.GetDirectoryName(Program.Self()), "wireguard.exe");
            var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
            int localPort = ((IPEndPoint)listener.LocalEndpoint).Port;
            try {
                using (var firewall = new Firewall()) {
                    firewall.Configure(binary, null, 0);
                    Assert(firewall.Verify(), "all owned filters exist");
                    Assert(!CanConnect(target, 443), "IPv4 external traffic blocked");
                    Assert(CanConnect(IPAddress.Loopback, localPort), "local engine communication permitted");
                }
                Assert(!CanConnect(target, 443), "lock persists after control session disposal");
                using (var recover = new Firewall()) recover.Release();
                Assert(CanConnect(target, 443), "recovery restores external access");
            } finally {
                listener.Stop();
                using (var recover = new Firewall()) recover.Release();
            }
            Console.WriteLine("PASS " + count + " isolated WFP checks. This is not a full torrent leak or real-server test.");
            return 0;
        }
        static bool CanConnect(IPAddress address, int port) {
            using (var client = new TcpClient(address.AddressFamily)) {
                try { Task task = client.ConnectAsync(address, port); return task.Wait(3000) && client.Connected; }
                catch { return false; }
            }
        }
        internal static string Fixture() {
            string key = Convert.ToBase64String(Enumerable.Range(1, 32).Select(x => (byte)x).ToArray());
            return "[Interface]\nPrivateKey = " + key + "\nAddress = 10.8.0.2/32\nDNS = 10.8.0.1\n\n[Peer]\nPublicKey = "
                + key + "\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 192.0.2.1:51820\n";
        }
        internal static int Run() {
            string fixture = Fixture();
            Profile profile = Profile.Parse(fixture);
            Assert(profile.Port == 51820 && profile.Endpoint.ToString() == "192.0.2.1", "endpoint");
            Assert(profile.Configuration.Contains("PersistentKeepalive = 25"), "pre-traffic handshake");
            Assert(!profile.ToString().Contains("PrivateKey"), "redaction");
            Reject(fixture + "PostUp = malicious\n", "UNSUPPORTED_PROFILE_FIELD");
            Reject(fixture.Replace("DNS = 10.8.0.1", "DNS = dns.example.com"), "LITERAL_IP_REQUIRED");
            Reject(fixture.Replace("192.0.2.1", "vpn.example.com"), "LITERAL_IP_REQUIRED");
            Reject(fixture.Replace("192.0.2.1", "127.0.0.1"), "INVALID_ENDPOINT");
            Reject(fixture.Replace("192.0.2.1", "0xc0000201"), "LITERAL_IP_REQUIRED");
            Reject(fixture.Replace("51820", "0"), "INVALID_NUMBER");
            Reject(fixture.Replace("0.0.0.0/0, ::/0", "0.0.0.0/1, 128.0.0.0/1"), "FULL_TUNNEL_REQUIRED");
            Reject(fixture.Replace("Address = 10.8.0.2/32", "Address = 10.8.0.2/0"), "INVALID_NUMBER");
            Reject(fixture + "Endpoint = 198.51.100.1:51820\n", "DUPLICATE_PROFILE_FIELD");
            Reject(fixture + "\n[Peer]\n", "SINGLE_PEER_REQUIRED");
            Reject(new string('x', 16385), "INVALID_PROFILE");
            Reject(fixture + "\0", "INVALID_PROFILE");
            Profile ipv6 = Profile.Parse(fixture.Replace("192.0.2.1:51820", "[2001:db8::1]:51820"));
            Assert(ipv6.Endpoint.ToString() == "2001:db8::1", "IPv6 literal");
            Profile v4 = Profile.Parse(fixture.Replace("0.0.0.0/0, ::/0", "0.0.0.0/0"));
            Assert(v4.Dns.Length == 1, "IPv4 profile with independent IPv6 blocking");
            string temp = Path.Combine(Path.GetTempPath(), "nuvio-vpn-test-" + Guid.NewGuid().ToString("N"));
            try {
                Dpapi.Write(temp, fixture);
                Assert(Dpapi.Read(temp) == fixture, "DPAPI roundtrip");
                Assert(!Encoding.UTF8.GetString(File.ReadAllBytes(temp)).Contains("PrivateKey"), "no plaintext secret on disk");
                Dpapi.Write(temp, profile.Configuration);
                Assert(Dpapi.Read(temp) == profile.Configuration, "atomic profile replacement");
            } finally { if (File.Exists(temp)) File.Delete(temp); }
            Console.WriteLine("PASS " + count + " native VPN checks; no service, adapter, or firewall was installed.");
            return 0;
        }
        static void Reject(string input, string code) {
            try { Profile.Parse(input); throw new Exception("Expected rejection: " + code); }
            catch (VpnError error) { Assert(error.Code == code && error.Message == code, code); }
        }
        static void Assert(bool condition, string test) {
            if (!condition) throw new Exception("Failed: " + test); count++;
        }
    }
}
