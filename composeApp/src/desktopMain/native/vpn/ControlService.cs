// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace NuvioVpn {
    static class Program {
        internal const string ServiceName = "NuvioVpnControl";
        internal const string TunnelName = "NuvioVpn";
        internal const string PipeName = "NuvioVpnControl-v1";
        internal static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "NuvioVpn");
        internal static readonly string State = Path.Combine(Root, "State");
        internal static readonly string Config = Path.Combine(State, TunnelName + ".conf.dpapi");
        internal static readonly string ArmedFile = Path.Combine(State, "armed");
        internal static readonly string OwnerFile = Path.Combine(State, "owner");
        internal static string Wireguard { get { return Path.Combine(Root, "wireguard.exe"); } }
        internal static string Wg { get { return Path.Combine(Root, "wg.exe"); } }

        static int Main(string[] args) {
            try {
                if (args.Length == 1 && args[0] == "service") { ServiceBase.Run(new ControlService()); return 0; }
                if (args.Length == 1 && args[0] == "client") return Client();
                if (args.Length == 1 && args[0] == "setup") {
                    var start = new ProcessStartInfo(Self(), "install " + WindowsIdentity.GetCurrent().User.Value) {
                        UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden };
                    using (Process child = Process.Start(start)) { child.WaitForExit(); return child.ExitCode; }
                }
                if (args.Length == 2 && args[0] == "install") { RequireAdmin(); Install(new SecurityIdentifier(args[1])); return 0; }
                if (args.Length == 1 && args[0] == "recover") { RequireAdmin(); Recover(false); return 0; }
                if (args.Length == 1 && args[0] == "uninstall") { RequireAdmin(); Recover(true); return 0; }
                if (args.Length == 1 && args[0] == "self-test") return NativeTests.Run();
                if (args.Length == 1 && args[0] == "firewall-smoke-test") {
                    RequireAdmin(); return NativeTests.FirewallSmokeTest();
                }
                if (args.Length == 1 && args[0] == "validate") { Profile.Parse(ReadBounded(Console.In, 16384)); Console.WriteLine("VALID"); return 0; }
                return 2;
            } catch (Exception error) {
                Console.WriteLine("ERROR\t" + SafeCode(error)); return 1;
            }
        }
        internal static string SafeCode(Exception error) { return error is VpnError ? ((VpnError)error).Code : "OPERATION_FAILED"; }
        internal static string Self() { return System.Reflection.Assembly.GetExecutingAssembly().Location; }
        internal static void RequireAdmin() {
            if (!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) throw new VpnError("ADMIN_REQUIRED");
        }
        internal static string ReadBounded(TextReader input, int maximum) {
            var builder = new StringBuilder(); int c;
            while ((c = input.Read()) >= 0 && c != '\n') {
                if (builder.Length >= maximum) throw new VpnError("REQUEST_TOO_LARGE");
                builder.Append((char)c);
            }
            // Both Console and StreamWriter use CRLF on Windows; the protocol delimiter is LF.
            return builder.ToString().TrimEnd('\r');
        }
        static int Client() {
            string request = ReadBounded(Console.In, 24000);
            using (var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous,
                TokenImpersonationLevel.Identification)) {
                try { pipe.Connect(3000); } catch (System.TimeoutException) { throw new VpnError("SETUP_REQUIRED"); }
                VerifyPipeServer(pipe);
                using (var writer = new StreamWriter(pipe, new UTF8Encoding(false), 1024, true)) {
                    writer.WriteLine(request); writer.Flush();
                    using (var reader = new StreamReader(pipe, Encoding.UTF8, false, 1024, true)) {
                        var read = Task.Factory.StartNew(() => ReadBounded(reader, 512));
                        if (!read.Wait(45000)) throw new VpnError("SERVICE_TIMEOUT");
                        string response = read.Result; Console.WriteLine(response);
                        return response.StartsWith("ERROR\t", StringComparison.Ordinal) ? 1 : 0;
                    }
                }
            }
        }
        internal static void VerifyPipeServer(NamedPipeClientStream pipe) {
            // A different local user can otherwise squat on a predictable pipe name and
            // claim "Connected". Authenticate the protected SYSTEM broker before sending keys.
            uint pid;
            if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out pid)) throw new VpnError("UNTRUSTED_SERVICE");
            if (!NativeScm.IsBrokerProcess(pid)) throw new VpnError("UNTRUSTED_SERVICE");
        }
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool GetNamedPipeServerProcessId(Microsoft.Win32.SafeHandles.SafePipeHandle pipe, out uint pid);
        static void Install(SecurityIdentifier owner) {
            if (IntPtr.Size != 8) throw new VpnError("UNSUPPORTED_ARCHITECTURE");
            string source = Path.GetDirectoryName(Self());
            VerifyRuntime(Path.Combine(source, "wireguard.exe"), "04C71FB1A555A6ACE40D3AE9113C90C4829D7CA1D6C903FD20A8D41A0E2BEF94");
            VerifyRuntime(Path.Combine(source, "wg.exe"), "E1F8CB5C9E30A878FA7EB0B2251BB4EB4946E669063E6FD5E4383CA4BD0DCAF2");
            EnsureNoReparse(Root); EnsureNoReparse(State);
            if (File.Exists(OwnerFile) && File.ReadAllText(OwnerFile).Trim() != owner.Value) throw new VpnError("OTHER_USER_VPN");
            if (File.Exists(ArmedFile)) throw new VpnError("RECOVERY_REQUIRED");
            StopService(ServiceName);
            Directory.CreateDirectory(Root); ProtectDirectory(Root, true);
            Directory.CreateDirectory(State); ProtectDirectory(State, false);
            foreach (string name in new[] { "NuvioVpn.exe", "wireguard.exe", "wg.exe" }) {
                string from = Path.Combine(source, name), to = Path.Combine(Root, name);
                EnsureNoReparse(to);
                if (!string.Equals(Path.GetFullPath(from), Path.GetFullPath(to), StringComparison.OrdinalIgnoreCase)) File.Copy(from, to, true);
                ProtectFile(to);
            }
            File.WriteAllText(OwnerFile, owner.Value, new UTF8Encoding(false));
            using (var scm = NativeScm.Open()) {
                scm.Install(ServiceName, "Nuvio VPN control", Quote(Path.Combine(Root, "NuvioVpn.exe")) + " service");
            }
            using (var service = new ServiceController(ServiceName)) {
                if (service.Status != ServiceControllerStatus.Running) service.Start();
                service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(15));
            }
        }
        internal static void VerifyRuntime(string path, string expected) {
            EnsureNoReparse(path);
            using (var stream = File.OpenRead(path)) using (var sha = SHA256.Create()) {
                if (BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "") != expected) throw new VpnError("RUNTIME_INVALID");
            }
        }
        internal static void VerifyInstalledRuntime() {
            VerifyRuntime(Wireguard, "04C71FB1A555A6ACE40D3AE9113C90C4829D7CA1D6C903FD20A8D41A0E2BEF94");
            VerifyRuntime(Wg, "E1F8CB5C9E30A878FA7EB0B2251BB4EB4946E669063E6FD5E4383CA4BD0DCAF2");
        }
        internal static void EnsureNoReparse(string path) {
            string current = Path.GetFullPath(path);
            while (!string.IsNullOrEmpty(current)) {
                if ((Directory.Exists(current) || File.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                    throw new VpnError("UNSAFE_PATH");
                current = Path.GetDirectoryName(current);
            }
        }
        static void ProtectDirectory(string path, bool usersRead) {
            var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
            acl.SetOwner(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null));
            foreach (WellKnownSidType sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
                acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            if (usersRead) acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null),
                FileSystemRights.ReadAndExecute, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.SetAccessControl(path, acl);
        }
        static void ProtectFile(string path) {
            // CopyFile may copy the user's source DACL. Never let a cached binary's owner or
            // explicit write permission survive installation into the SYSTEM service directory.
            var acl = new FileSecurity(); acl.SetAccessRuleProtection(true, false);
            acl.SetOwner(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null));
            foreach (WellKnownSidType sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
                acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl, AccessControlType.Allow));
            acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null),
                FileSystemRights.ReadAndExecute, AccessControlType.Allow));
            File.SetAccessControl(path, acl);
        }
        internal static string Quote(string text) {
            if (text.Contains("\"") || text.Contains("\r") || text.Contains("\n")) throw new VpnError("UNSAFE_PATH");
            return "\"" + text + "\"";
        }
        internal static string Run(string executable, string arguments, int timeout) {
            using (var process = new Process()) {
                process.StartInfo = new ProcessStartInfo(executable, arguments) { UseShellExecute = false,
                    CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
                process.Start();
                var output = process.StandardOutput.ReadToEndAsync();
                var errors = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(timeout)) { process.Kill(); throw new VpnError("SERVICE_TIMEOUT"); }
                if (process.ExitCode != 0) throw new VpnError("TUNNEL_FAILED");
                return output.Result; // stderr is deliberately never returned, logged, or attached to errors.
            }
        }
        internal static void StopService(string name) {
            using (var service = new ServiceController(name)) {
                try {
                    if (service.Status != ServiceControllerStatus.Stopped) {
                        service.Stop(); service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(15));
                    }
                } catch (InvalidOperationException error) {
                    var native = error.InnerException as System.ComponentModel.Win32Exception;
                    if (native == null || native.NativeErrorCode != 1060) throw;
                }
            }
        }
        internal static void AssertNoTorrentProcesses() {
            // Conservative recovery: never remove the full-device lock while any known torrent engine is live.
            foreach (Process process in Process.GetProcesses()) using (process) {
                string name = process.ProcessName;
                if (name.StartsWith("TorrServer", StringComparison.OrdinalIgnoreCase)) throw new VpnError("TORRENT_STILL_RUNNING");
            }
        }
        static void Recover(bool uninstall) {
            AssertNoTorrentProcesses(); StopService(ServiceName); StopService("WireGuardTunnel$" + TunnelName);
            using (var firewall = new Firewall()) firewall.Release();
            if (File.Exists(ArmedFile)) File.Delete(ArmedFile);
            if (uninstall) {
                using (var scm = NativeScm.Open()) { scm.Delete("WireGuardTunnel$" + TunnelName); scm.Delete(ServiceName); }
                if (File.Exists(Config)) File.Delete(Config);
                if (File.Exists(OwnerFile)) File.Delete(OwnerFile);
                // Do not remove the shared WireGuard driver or any other VPN service.
            }
        }
    }

    sealed class ControlService : ServiceBase {
        readonly object sync = new object();
        Firewall firewall;
        Profile profile;
        volatile bool stopping;
        bool armed, connected;
        DateTime connectStarted;
        Thread worker;
        NamedPipeServerStream currentPipe;
        internal ControlService() { ServiceName = Program.ServiceName; CanStop = true; AutoLog = false; }
        protected override void OnStart(string[] args) {
            Program.EnsureNoReparse(Program.State); Program.VerifyInstalledRuntime();
            firewall = new Firewall(); armed = File.Exists(Program.ArmedFile);
            if (armed) {
                // Atomically replace all prior interface permits before considering a new connection.
                firewall.Configure(Program.Wireguard, null, 0);
                Program.StopService("WireGuardTunnel$" + Program.TunnelName);
            }
            if (File.Exists(Program.Config)) profile = Profile.Parse(Dpapi.Read(Program.Config));
            worker = new Thread(Serve) { IsBackground = true }; worker.Start();
        }
        protected override void OnStop() {
            stopping = true;
            if (currentPipe != null) currentPipe.Dispose();
            if (worker != null) worker.Join(5000);
            if (firewall != null) firewall.Dispose(); // Persistent lock survives service stop/crash.
        }
        void Serve() {
            var security = new PipeSecurity(); security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null), PipeAccessRights.FullControl, AccessControlType.Deny));
            string owner = File.ReadAllText(Program.OwnerFile).Trim();
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(owner), PipeAccessRights.ReadWrite, AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
            while (!stopping) {
                try {
                    using (var pipe = new NamedPipeServerStream(Program.PipeName, PipeDirection.InOut, 1,
                        PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 4096, 4096, security)) {
                        currentPipe = pipe; pipe.WaitForConnection();
                        string caller = null; pipe.RunAsClient(() => caller = WindowsIdentity.GetCurrent(true).User.Value);
                        if (caller != owner && caller != "S-1-5-18") continue;
                        using (var reader = new StreamReader(pipe, Encoding.UTF8, false, 1024, true))
                        using (var writer = new StreamWriter(pipe, new UTF8Encoding(false), 1024, true)) {
                            var read = Task.Factory.StartNew(() => Program.ReadBounded(reader, 24000));
                            if (!read.Wait(5000)) continue;
                            string response;
                            try { lock (sync) response = Dispatch(read.Result); }
                            catch (Exception error) { connected = false; response = "ERROR\t" + Program.SafeCode(error); }
                            writer.WriteLine(response); writer.Flush();
                        }
                    }
                } catch { if (stopping) break; Thread.Sleep(100); }
            }
        }
        string Dispatch(string request) {
            string[] parts = request.Split('\t'); string command = parts[0];
            if (command == "status" && parts.Length == 1) return Status();
            if (command == "import" && parts.Length == 2) {
                Program.AssertNoTorrentProcesses();
                Profile replacement;
                try { replacement = Profile.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(parts[1]))); }
                catch (FormatException) { throw new VpnError("INVALID_PROFILE"); }
                if (armed) Hold();
                Dpapi.Write(Program.Config, replacement.Configuration); profile = replacement;
                return Status();
            }
            if (command == "delete" && parts.Length == 1) {
                Program.AssertNoTorrentProcesses(); if (armed) Hold();
                if (File.Exists(Program.Config)) File.Delete(Program.Config); profile = null;
                return Status();
            }
            if (command == "connect" && parts.Length == 1) {
                Program.AssertNoTorrentProcesses();
                if (profile == null) throw new VpnError("PROFILE_REQUIRED");
                Program.VerifyInstalledRuntime();
                File.WriteAllText(Program.ArmedFile, "1"); armed = true; connected = false;
                firewall.Configure(Program.Wireguard, profile, 0);
                Program.StopService("WireGuardTunnel$" + Program.TunnelName);
                // The independent lock is in place before the official service creates routes or sockets.
                using (var scm = NativeScm.Open()) scm.Delete("WireGuardTunnel$" + Program.TunnelName);
                Program.Run(Program.Wireguard, "/installtunnelservice " + Program.Quote(Program.Config), 15000);
                using (var tunnel = new ServiceController("WireGuardTunnel$" + Program.TunnelName))
                    tunnel.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(15));
                ulong luid = FindTunnel();
                if (luid == 0) throw new VpnError("TUNNEL_FAILED");
                firewall.Configure(Program.Wireguard, profile, luid);
                connectStarted = DateTime.UtcNow; connected = true;
                return Status();
            }
            if (command == "arm" && parts.Length == 1) { Hold(); return Status(); }
            if (command == "hold" && parts.Length == 1) { Program.AssertNoTorrentProcesses(); Hold(); return Status(); }
            if (command == "off" && parts.Length == 1) {
                Program.AssertNoTorrentProcesses();
                if (armed) firewall.Configure(Program.Wireguard, null, 0);
                connected = false; Program.StopService("WireGuardTunnel$" + Program.TunnelName);
                firewall.Release();
                if (File.Exists(Program.ArmedFile)) File.Delete(Program.ArmedFile); armed = false;
                return Status();
            }
            throw new VpnError("INVALID_REQUEST");
        }
        void Hold() {
            File.WriteAllText(Program.ArmedFile, "1"); armed = true; connected = false;
            firewall.Configure(Program.Wireguard, null, 0);
            Program.StopService("WireGuardTunnel$" + Program.TunnelName);
        }
        string Status() {
            string status = armed ? "Blocked" : "Off";
            bool verified = false;
            if (armed && connected && firewall.Verify() && FindTunnel() != 0) {
                try {
                    string output = Program.Run(Program.Wg, "show " + Program.TunnelName + " latest-handshakes", 2000);
                    long timestamp; string[] fields = output.Trim().Split('\t');
                    if (fields.Length == 2 && long.TryParse(fields[1], out timestamp) && timestamp > 0) {
                        long now = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalSeconds;
                        if (now - timestamp < 180 && timestamp <= now + 5) { status = "Connected"; verified = true; }
                    }
                    if (!verified && (DateTime.UtcNow - connectStarted).TotalSeconds < 30) status = "Connecting";
                } catch { status = "Blocked"; }
            }
            return "STATE\t" + status + "\t" + (profile == null ? "0" : "1") + "\t" + (verified ? "1" : "0");
        }
        static ulong FindTunnel() {
            foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces()) {
                if (adapter.Name != Program.TunnelName || adapter.OperationalStatus != OperationalStatus.Up) continue;
                ulong luid;
                Guid guid = new Guid(adapter.Id);
                if (ConvertInterfaceGuidToLuid(ref guid, out luid) == 0) return luid;
            }
            return 0;
        }
        [DllImport("iphlpapi.dll")] static extern uint ConvertInterfaceGuidToLuid(ref Guid guid, out ulong luid);
    }

    static class Dpapi {
        [StructLayout(LayoutKind.Sequential)] struct Blob { internal int Length; internal IntPtr Data; }
        [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CryptProtectData(ref Blob input,
            string description, IntPtr entropy, IntPtr reserved, IntPtr prompt, uint flags, out Blob output);
        [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CryptUnprotectData(ref Blob input,
            out IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, uint flags, out Blob output);
        [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
        internal static void Write(string path, string configuration) {
            byte[] bytes = Encoding.UTF8.GetBytes(configuration);
            using (var arena = new Arena()) {
                var input = new Blob { Length = bytes.Length, Data = arena.Bytes(bytes) }; Blob output;
                if (!CryptProtectData(ref input, Program.TunnelName, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)) throw new VpnError("KEY_STORAGE_FAILED");
                try {
                    var encrypted = new byte[output.Length]; Marshal.Copy(output.Data, encrypted, 0, encrypted.Length);
                    string temp = path + ".new"; Program.EnsureNoReparse(temp); File.WriteAllBytes(temp, encrypted);
                    if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
                } finally { LocalFree(output.Data); Array.Clear(bytes, 0, bytes.Length); }
            }
        }
        internal static string Read(string path) {
            byte[] encrypted = File.ReadAllBytes(path);
            using (var arena = new Arena()) {
                var input = new Blob { Length = encrypted.Length, Data = arena.Bytes(encrypted) }; Blob output; IntPtr description;
                if (!CryptUnprotectData(ref input, out description, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)) throw new VpnError("KEY_STORAGE_FAILED");
                try {
                    if (Marshal.PtrToStringUni(description) != Program.TunnelName) throw new VpnError("KEY_STORAGE_FAILED");
                    var bytes = new byte[output.Length]; Marshal.Copy(output.Data, bytes, 0, bytes.Length);
                    string result = Encoding.UTF8.GetString(bytes); Array.Clear(bytes, 0, bytes.Length); return result;
                } finally { LocalFree(description); LocalFree(output.Data); }
            }
        }
    }

    sealed class NativeScm : IDisposable {
        IntPtr handle;
        internal static bool IsBrokerProcess(uint pid) {
            // SCM read access works for standard users; querying a SYSTEM process token does not.
            IntPtr scm = OpenSCManager(null, null, 1);
            if (scm == IntPtr.Zero) return false;
            IntPtr service = IntPtr.Zero;
            try {
                service = OpenService(scm, Program.ServiceName, 5); // QUERY_CONFIG | QUERY_STATUS
                if (service == IntPtr.Zero) return false;
                using (var arena = new Arena()) {
                    uint needed; IntPtr status = arena.Alloc(36);
                    if (!QueryServiceStatusEx(service, 0, status, 36, out needed) || Marshal.ReadInt32(status, 4) != 4 ||
                        unchecked((uint)Marshal.ReadInt32(status, 28)) != pid) return false;
                    QueryServiceConfig(service, IntPtr.Zero, 0, out needed);
                    if (needed < 64 || needed > 8192) return false;
                    IntPtr config = arena.Alloc((int)needed);
                    if (!QueryServiceConfig(service, config, needed, out needed)) return false;
                    string account = Marshal.PtrToStringUni(Marshal.ReadIntPtr(config, 48));
                    string binary = Marshal.PtrToStringUni(Marshal.ReadIntPtr(config, 16));
                    return string.Equals(account, "LocalSystem", StringComparison.OrdinalIgnoreCase) &&
                        string.Equals(binary, Program.Quote(Path.Combine(Program.Root, "NuvioVpn.exe")) + " service", StringComparison.OrdinalIgnoreCase);
                }
            } finally {
                if (service != IntPtr.Zero) CloseServiceHandle(service);
                CloseServiceHandle(scm);
            }
        }
        internal static NativeScm Open() {
            IntPtr h = OpenSCManager(null, null, 0xF003F);
            if (h == IntPtr.Zero) throw new VpnError("ADMIN_REQUIRED"); return new NativeScm { handle = h };
        }
        internal void Install(string name, string display, string binary) {
            IntPtr existing = OpenService(handle, name, 0xF01FF);
            if (existing != IntPtr.Zero) { ConfigureRecovery(existing); CloseServiceHandle(existing); return; }
            IntPtr service = CreateService(handle, name, display, 0xF01FF, 0x10, 2, 1, binary, null,
                IntPtr.Zero, "BFE\0Tcpip\0\0", null, null);
            if (service == IntPtr.Zero) throw new VpnError("INSTALL_FAILED");
            try { ConfigureRecovery(service); } finally { CloseServiceHandle(service); }
        }
        static void ConfigureRecovery(IntPtr service) {
            using (var arena = new Arena()) {
                IntPtr actions = arena.Alloc(24);
                for (int i = 0; i < 3; i++) { Marshal.WriteInt32(actions, i * 8, 1); Marshal.WriteInt32(actions, i * 8 + 4, (i + 1) * 5000); }
                IntPtr config = arena.Alloc(40); Marshal.WriteInt32(config, 0, 86400); Marshal.WriteInt32(config, 24, 3);
                Marshal.WriteIntPtr(config, 32, actions);
                if (!ChangeServiceConfig2(service, 2, config)) throw new VpnError("INSTALL_FAILED");
            }
        }
        internal void Delete(string name) {
            IntPtr service = OpenService(handle, name, 0x10000);
            if (service == IntPtr.Zero) {
                if (Marshal.GetLastWin32Error() == 1060) return; throw new VpnError("SERVICE_FAILED");
            }
            try { if (!DeleteService(service)) throw new VpnError("SERVICE_FAILED"); } finally { CloseServiceHandle(service); }
        }
        public void Dispose() { CloseServiceHandle(handle); }
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr OpenSCManager(string machine, string database, uint access);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr OpenService(IntPtr scm, string name, uint access);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr CreateService(IntPtr scm, string name, string display,
            uint access, uint type, uint start, uint error, string binary, string group, IntPtr tag, string dependencies, string account, string password);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool DeleteService(IntPtr service);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool QueryServiceStatusEx(IntPtr service, int level, IntPtr status, uint size, out uint needed);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool QueryServiceConfig(IntPtr service, IntPtr config, uint size, out uint needed);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool ChangeServiceConfig2(IntPtr service, uint level, IntPtr config);
        [DllImport("advapi32.dll")] static extern bool CloseServiceHandle(IntPtr handle);
    }
}
