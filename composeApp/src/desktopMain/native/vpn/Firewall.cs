// SPDX-License-Identifier: GPL-3.0-or-later
// Windows SDK WFP definitions; ABI sizes checked against WireGuard's MIT-licensed firewall tests.
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace NuvioVpn {
    sealed class Arena : IDisposable {
        readonly List<IntPtr> allocations = new List<IntPtr>();
        internal IntPtr Alloc(int length) {
            IntPtr p = Marshal.AllocHGlobal(length); allocations.Add(p);
            Marshal.Copy(new byte[length], 0, p, length); return p;
        }
        internal IntPtr Bytes(byte[] bytes) { IntPtr p = Alloc(bytes.Length); Marshal.Copy(bytes, 0, p, bytes.Length); return p; }
        internal IntPtr Text(string text) { IntPtr p = Marshal.StringToHGlobalUni(text); allocations.Add(p); return p; }
        internal IntPtr UInt64(ulong value) { IntPtr p = Alloc(8); Marshal.WriteInt64(p, unchecked((long)value)); return p; }
        public void Dispose() { foreach (IntPtr p in allocations) Marshal.FreeHGlobal(p); }
    }

    sealed class Firewall : IDisposable {
        static readonly Guid SubLayer = new Guid("e685047a-0b52-45bb-b057-6071f9309fd9");
        static readonly Guid[] Layers = {
            new Guid("c38d57d1-05a7-4c33-904f-7fbceee60e82"), new Guid("e1cd9fe7-f4b5-4273-96c0-592e487b8650"),
            new Guid("4a72393b-319f-44bc-84c3-ba54dcb3b6b4"), new Guid("a3b42c97-9f04-4672-b87e-cee9c483257f") };
        static readonly Guid Flags = new Guid("632ce23b-5167-435c-86d7-e903684a8a0c");
        static readonly Guid Interface = new Guid("4cd62a49-59c3-4969-b7f3-bda5d32890a4");
        static readonly Guid App = new Guid("d78e1e87-8644-4ea5-9437-d809ecefc971");
        static readonly Guid Protocol = new Guid("3971ef2b-623e-4f9a-8cb1-6e79b806b9a7");
        static readonly Guid LocalPort = new Guid("0c1ba1af-5765-453f-af22-a8f791ac775b");
        static readonly Guid RemotePort = new Guid("c35a604d-d22b-4e1a-91b4-68f674ee674b");
        static readonly Guid RemoteAddress = new Guid("b235ae9a-1d64-49b8-a44c-5ff3d9095045");
        IntPtr engine;
        int nextRule;
        internal bool Armed { get; private set; }
        internal Firewall() {
            if (IntPtr.Size != 8) throw new VpnError("UNSUPPORTED_ARCHITECTURE");
            Check(FwpmEngineOpen0(null, 10, IntPtr.Zero, IntPtr.Zero, out engine));
        }

        // Stable keys allow recovery after a service crash; no dynamic WFP session is used.
        static Guid RuleKey(int index) { return new Guid("fb4e2c5d-1b66-40c2-a670-" + index.ToString("x12")); }
        internal void Configure(string wireguardPath, Profile profile, ulong tunnelLuid) {
            Transaction(delegate {
                RemoveFilters();
                using (var arena = new Arena()) {
                    IntPtr sub = arena.Alloc(72);
                    WriteGuid(sub, 0, SubLayer); Marshal.WriteIntPtr(sub, 16, arena.Text("Nuvio VPN protection"));
                    Marshal.WriteInt32(sub, 32, 1); Marshal.WriteInt16(sub, 64, unchecked((short)65534));
                    uint added = FwpmSubLayerAdd0(engine, sub, IntPtr.Zero);
                    if (added != 0 && added != 0x80320009) Check(added); // FWP_E_ALREADY_EXISTS
                    IntPtr appId;
                    Check(FwpmGetAppIdFromFileName0(wireguardPath, out appId));
                    try {
                        for (int layer = 0; layer < Layers.Length; layer++) {
                            Add(arena, layer, 0, false, new Condition[0]);
                            Add(arena, layer, 14, true, new[] { Scalar(Flags, 3, 1, 6) });
                            if (tunnelLuid != 0)
                                Add(arena, layer, 12, true, new[] { Pointer(Interface, 4, arena.UInt64(tunnelLuid)) });
                            if (profile != null && (layer < 2) == (profile.Endpoint.AddressFamily == AddressFamily.InterNetwork)) {
                                Add(arena, layer, 15, true, new[] {
                                    Pointer(App, 12, appId), Scalar(Protocol, 1, 17, 0),
                                    Scalar(RemotePort, 2, profile.Port, 0), Address(arena, profile.Endpoint) });
                            }
                            // Restricted DHCP exceptions; no blanket LAN/network-service exemption.
                            Add(arena, layer, 11, true, new[] { Scalar(Protocol, 1, 17, 0),
                                Scalar(LocalPort, 2, layer < 2 ? 68u : 546u, 0), Scalar(RemotePort, 2, layer < 2 ? 67u : 547u, 0) });
                            if (layer >= 2) {
                                foreach (uint type in new uint[] { 133, 134, 135, 136 })
                                    Add(arena, layer, 11, true, new[] { Scalar(Protocol, 1, 58, 0),
                                        Scalar(LocalPort, 2, type, 0), Scalar(RemotePort, 2, 0, 0) });
                            }
                        }
                    } finally { FwpmFreeMemory0(ref appId); }
                }
            });
            Armed = true;
        }

        internal bool Verify() {
            if (!Armed) return false;
            // Check every owned filter, not just the block rules or a cached preference.
            for (int i = 0; i < nextRule; i++) {
                Guid key = RuleKey(i); IntPtr filter;
                if (FwpmFilterGetByKey0(engine, ref key, out filter) != 0) return false;
                FwpmFreeMemory0(ref filter);
            }
            return true;
        }
        internal void Release() {
            Transaction(delegate {
                RemoveFilters(); uint deleted = FwpmSubLayerDeleteByKey0(engine, ref SubLayerHolder.Value);
                if (deleted != 0 && deleted != 0x80320007) Check(deleted);
            });
            Armed = false;
        }
        static class SubLayerHolder { internal static Guid Value = SubLayer; }
        void RemoveFilters() {
            for (int i = 0; i < 128; i++) {
                Guid key = RuleKey(i); uint deleted = FwpmFilterDeleteByKey0(engine, ref key);
                if (deleted != 0 && deleted != 0x80320003) Check(deleted); // FILTER_NOT_FOUND
            }
            nextRule = 0;
        }
        void Transaction(Action body) {
            Check(FwpmTransactionBegin0(engine, 0));
            try { body(); Check(FwpmTransactionCommit0(engine)); } catch { FwpmTransactionAbort0(engine); throw; }
        }
        struct Condition { internal Guid Key; internal int Type, Match; internal ulong Value; internal IntPtr Pointer; }
        static Condition Scalar(Guid key, int type, uint value, int match) { return new Condition { Key = key, Type = type, Value = value, Match = match }; }
        static Condition Pointer(Guid key, int type, IntPtr pointer) { return new Condition { Key = key, Type = type, Pointer = pointer }; }
        static Condition Address(Arena arena, IPAddress ip) {
            byte[] b = ip.GetAddressBytes();
            return b.Length == 4 ? Scalar(RemoteAddress, 3, ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3], 0)
                : Pointer(RemoteAddress, 11, arena.Bytes(b));
        }
        void Add(Arena arena, int layer, byte weight, bool permit, Condition[] conditions) {
            IntPtr buffer = arena.Alloc(conditions.Length * 40);
            for (int i = 0; i < conditions.Length; i++) {
                IntPtr condition = IntPtr.Add(buffer, i * 40); Condition value = conditions[i];
                WriteGuid(condition, 0, value.Key); Marshal.WriteInt32(condition, 16, value.Match);
                Marshal.WriteInt32(condition, 24, value.Type);
                Marshal.WriteInt64(condition, 32, value.Pointer != IntPtr.Zero ? value.Pointer.ToInt64() : unchecked((long)value.Value));
            }
            IntPtr filter = arena.Alloc(200);
            WriteGuid(filter, 0, RuleKey(nextRule++)); Marshal.WriteIntPtr(filter, 16, arena.Text("Nuvio VPN"));
            Marshal.WriteInt32(filter, 32, 1); WriteGuid(filter, 64, Layers[layer]); WriteGuid(filter, 80, SubLayer);
            Marshal.WriteInt32(filter, 96, 1); Marshal.WriteByte(filter, 104, weight);
            Marshal.WriteInt32(filter, 112, conditions.Length); Marshal.WriteIntPtr(filter, 120, buffer);
            Marshal.WriteInt32(filter, 128, permit ? 0x1002 : 0x1001);
            ulong id; Check(FwpmFilterAdd0(engine, filter, IntPtr.Zero, out id));
        }
        static void WriteGuid(IntPtr p, int offset, Guid value) { Marshal.Copy(value.ToByteArray(), 0, IntPtr.Add(p, offset), 16); }
        static void Check(uint code) {
            if (code == 0) return;
            // CI diagnostics contain only a numeric Windows result, never profile data.
            if (Environment.GetEnvironmentVariable("GITHUB_ACTIONS") == "true")
                Console.Error.WriteLine("WFP_RESULT " + code.ToString("X8"));
            throw new VpnError("FIREWALL_FAILED");
        }
        public void Dispose() { if (engine != IntPtr.Zero) { FwpmEngineClose0(engine); engine = IntPtr.Zero; } }
        [DllImport("fwpuclnt.dll", CharSet = CharSet.Unicode)] static extern uint FwpmEngineOpen0(string server, uint auth, IntPtr identity, IntPtr session, out IntPtr engine);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmEngineClose0(IntPtr engine);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionBegin0(IntPtr engine, uint flags);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionCommit0(IntPtr engine);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionAbort0(IntPtr engine);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmSubLayerAdd0(IntPtr engine, IntPtr sublayer, IntPtr security);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmSubLayerDeleteByKey0(IntPtr engine, ref Guid key);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterAdd0(IntPtr engine, IntPtr filter, IntPtr security, out ulong id);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterDeleteByKey0(IntPtr engine, ref Guid key);
        [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterGetByKey0(IntPtr engine, ref Guid key, out IntPtr filter);
        [DllImport("fwpuclnt.dll", CharSet = CharSet.Unicode)] static extern uint FwpmGetAppIdFromFileName0(string file, out IntPtr appId);
        [DllImport("fwpuclnt.dll")] static extern void FwpmFreeMemory0(ref IntPtr memory);
    }
}
