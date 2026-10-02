// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace NuvioVpn {
    sealed class VpnError : Exception {
        internal readonly string Code;
        internal VpnError(string code) : base(code) { Code = code; }
    }

    // Never include the input or individual keys in an exception or ToString().
    sealed class Profile {
        internal readonly string Configuration;
        internal readonly IPAddress Endpoint;
        internal readonly ushort Port;
        internal readonly IPAddress[] Dns;
        internal Profile(string configuration, IPAddress endpoint, ushort port, IPAddress[] dns) {
            Configuration = configuration; Endpoint = endpoint; Port = port; Dns = dns;
        }
        public override string ToString() { return "WireGuard profile [redacted]"; }

        internal static Profile Parse(string input) {
            if (input == null || input.Length > 16384 || input.IndexOf('\0') >= 0) throw new VpnError("INVALID_PROFILE");
            var fields = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            string section = "";
            int interfaces = 0, peers = 0;
            foreach (string original in input.Split('\n')) {
                string line = original.Split('#')[0].Trim();
                if (line.Length == 0) continue;
                if (line == "[Interface]") { section = "Interface"; interfaces++; continue; }
                if (line == "[Peer]") { section = "Peer"; peers++; continue; }
                int equals = line.IndexOf('=');
                if (equals < 1 || section.Length == 0) throw new VpnError("INVALID_PROFILE");
                string name = line.Substring(0, equals).Trim();
                string key = section + "." + name;
                var allowed = section == "Interface"
                    ? new[] { "PrivateKey", "Address", "DNS", "MTU", "ListenPort" }
                    : new[] { "PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive" };
                if (!allowed.Contains(name, StringComparer.OrdinalIgnoreCase)) throw new VpnError("UNSUPPORTED_PROFILE_FIELD");
                if (fields.ContainsKey(key)) throw new VpnError("DUPLICATE_PROFILE_FIELD");
                fields.Add(key, line.Substring(equals + 1).Trim());
            }
            if (interfaces != 1 || peers != 1) throw new VpnError("SINGLE_PEER_REQUIRED");
            ValidateKey(Required(fields, "Interface.PrivateKey"));
            ValidateKey(Required(fields, "Peer.PublicKey"));
            if (fields.ContainsKey("Peer.PresharedKey")) ValidateKey(fields["Peer.PresharedKey"]);
            foreach (string address in Required(fields, "Interface.Address").Split(',')) ValidateCidr(address.Trim());
            string[] routes = Required(fields, "Peer.AllowedIPs").Split(',').Select(x => x.Trim()).ToArray();
            // IPv6 may be omitted only because the independent firewall then blocks it outside the tunnel.
            if (!routes.Contains("0.0.0.0/0") || routes.Any(x => x != "0.0.0.0/0" && x != "::/0") || routes.Distinct().Count() != routes.Length)
                throw new VpnError("FULL_TUNNEL_REQUIRED");
            string[] dnsText = Required(fields, "Interface.DNS").Split(',').Select(x => x.Trim()).ToArray();
            if (dnsText.Length > 4) throw new VpnError("INVALID_DNS");
            IPAddress[] dns = dnsText.Select(LiteralAddress).ToArray();
            string endpointText = Required(fields, "Peer.Endpoint");
            int separator = endpointText.LastIndexOf(':');
            if (separator < 1) throw new VpnError("INVALID_ENDPOINT");
            string host = endpointText.Substring(0, separator);
            if (host.StartsWith("[") && host.EndsWith("]")) host = host.Substring(1, host.Length - 2);
            else if (host.Contains(":")) throw new VpnError("INVALID_ENDPOINT");
            IPAddress endpoint = LiteralAddress(host);
            if (IPAddress.IsLoopback(endpoint) || endpoint.Equals(IPAddress.Any) || endpoint.Equals(IPAddress.IPv6Any))
                throw new VpnError("INVALID_ENDPOINT");
            int port = Number(endpointText.Substring(separator + 1), 1, 65535);
            CheckOptional(fields, "Interface.MTU", 1280, 1500);
            CheckOptional(fields, "Interface.ListenPort", 0, 65535);
            CheckOptional(fields, "Peer.PersistentKeepalive", 0, 65535);
            // Generate an authenticated handshake before any torrent is allowed to start, and keep idle status observable.
            fields["Peer.PersistentKeepalive"] = "25";
            var canonical = new StringBuilder("[Interface]\n");
            foreach (string name in new[] { "PrivateKey", "Address", "DNS", "MTU", "ListenPort" })
                if (fields.ContainsKey("Interface." + name)) canonical.Append(name).Append(" = ").Append(fields["Interface." + name]).Append('\n');
            canonical.Append("\n[Peer]\n");
            foreach (string name in new[] { "PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive" })
                if (fields.ContainsKey("Peer." + name)) canonical.Append(name).Append(" = ").Append(fields["Peer." + name]).Append('\n');
            return new Profile(canonical.ToString(), endpoint, (ushort)port, dns);
        }
        static string Required(Dictionary<string, string> fields, string key) {
            string value;
            if (!fields.TryGetValue(key, out value) || value.Length == 0) throw new VpnError("INVALID_PROFILE");
            return value;
        }
        static void ValidateKey(string value) {
            try {
                byte[] decoded = Convert.FromBase64String(value);
                if (value.Length != 44 || decoded.Length != 32 || decoded.All(x => x == 0) || Convert.ToBase64String(decoded) != value)
                    throw new VpnError("INVALID_KEY");
                Array.Clear(decoded, 0, decoded.Length);
            } catch (FormatException) { throw new VpnError("INVALID_KEY"); }
        }
        internal static IPAddress LiteralAddress(string text) {
            IPAddress result;
            // Do not perform DNS resolution or accept historical short/hexadecimal IPv4 forms.
            if (text.Contains("%") || !IPAddress.TryParse(text, out result)) throw new VpnError("LITERAL_IP_REQUIRED");
            if (result.AddressFamily == AddressFamily.InterNetwork && result.ToString() != text) throw new VpnError("LITERAL_IP_REQUIRED");
            if (result.IsIPv4MappedToIPv6) throw new VpnError("LITERAL_IP_REQUIRED");
            return result;
        }
        static void ValidateCidr(string value) {
            string[] parts = value.Split('/');
            if (parts.Length != 2) throw new VpnError("INVALID_ADDRESS");
            IPAddress ip = LiteralAddress(parts[0]);
            Number(parts[1], 1, ip.AddressFamily == AddressFamily.InterNetwork ? 32 : 128);
        }
        static int Number(string value, int minimum, int maximum) {
            int result;
            if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out result) || result < minimum || result > maximum)
                throw new VpnError("INVALID_NUMBER");
            return result;
        }
        static void CheckOptional(Dictionary<string, string> fields, string key, int minimum, int maximum) {
            if (fields.ContainsKey(key)) Number(fields[key], minimum, maximum);
        }
    }
}
