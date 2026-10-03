// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.IO;
using System.ServiceProcess;

namespace NuvioVpn {
    // MSI deferred SYSTEM actions. Optional VPN is never installed by a fresh app install.
    // Rollback snapshots contain only protected binaries and machine-encrypted profile data.
    static class InstallerMaintenance {
        static string Backup { get { return Path.Combine(Program.Root, "InstallerRollback"); } }
        static readonly string[] Files = { "NuvioVpn.exe", "wireguard.exe", "wg.exe", "State/owner", "State/NuvioVpn.conf.dpapi", "State/armed" };
        internal static void Run(string command) {
            switch (command) {
                case "installer-prepare": Prepare(); break;
                case "installer-resume": Resume(false); break;
                case "installer-rollback": Resume(true); Cleanup(); break;
                case "installer-remove": Remove(); break;
                case "installer-commit": Cleanup(); break;
                default: throw new VpnError("INVALID_COMMAND");
            }
        }
        static void Prepare() {
            Program.EnsureNoReparse(Program.Root); Program.EnsureNoReparse(Backup);
            if (!File.Exists(Program.OwnerFile)) return;
            Program.AssertNoTorrentProcesses(); Program.VerifyInstalledRuntime();
            if (Directory.Exists(Backup)) throw new VpnError("RECOVERY_REQUIRED");
            Directory.CreateDirectory(Backup); Program.ProtectDirectory(Backup, false);
            Directory.CreateDirectory(Path.Combine(Backup, "State"));
            foreach (string name in Files) {
                string source = Path.Combine(Program.Root, name);
                Program.EnsureNoReparse(source);
                if (File.Exists(source)) { File.Copy(source, Path.Combine(Backup, name)); Program.ProtectFile(Path.Combine(Backup, name), false); }
            }
            File.WriteAllText(Path.Combine(Backup, "prepared"), "1");
            Program.ProtectFile(Path.Combine(Backup, "prepared"), false);
            // Drop old interface permits before stopping either service; no cleartext gap.
            if (File.Exists(Program.ArmedFile)) using (var firewall = new Firewall()) firewall.Configure(Program.Wireguard, null, 0);
            Program.StopService("WireGuardTunnel$" + Program.TunnelName);
            Program.StopService(Program.ServiceName);
        }
        static void Resume(bool rollback) {
            Program.EnsureNoReparse(Program.Root); Program.EnsureNoReparse(Backup);
            if (!File.Exists(Path.Combine(Backup, "prepared"))) return;
            Program.AssertNoTorrentProcesses();
            Program.StopService(Program.ServiceName); Program.StopService("WireGuardTunnel$" + Program.TunnelName);
            if (rollback) {
                Directory.CreateDirectory(Program.Root); Program.ProtectDirectory(Program.Root, true);
                Directory.CreateDirectory(Program.State); Program.ProtectDirectory(Program.State, false);
                foreach (string name in Files) {
                    string source = Path.Combine(Backup, name), target = Path.Combine(Program.Root, name);
                    Program.EnsureNoReparse(source); Program.EnsureNoReparse(target);
                    if (File.Exists(source)) { File.Copy(source, target, true); Program.ProtectFile(target, !name.StartsWith("State/", StringComparison.Ordinal)); }
                    else if (File.Exists(target)) File.Delete(target);
                }
            } else {
                string target = Path.Combine(Program.Root, "NuvioVpn.exe");
                Program.EnsureNoReparse(target);
                File.Copy(Program.Self(), target, true); Program.ProtectFile(target);
            }
            Program.VerifyInstalledRuntime();
            using (var firewall = new Firewall()) {
                if (File.Exists(Program.ArmedFile)) firewall.Configure(Program.Wireguard, null, 0);
                else firewall.Release();
            }
            using (var scm = NativeScm.Open()) {
                scm.Install(Program.ServiceName, "Nuvio VPN control", Program.Quote(Path.Combine(Program.Root, "NuvioVpn.exe")) + " service");
            }
            using (var service = new ServiceController(Program.ServiceName)) {
                service.Start(); service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(15));
            }
        }
        static void Remove() {
            Program.EnsureNoReparse(Program.Root);
            if (!File.Exists(Program.OwnerFile)) return;
            Program.Recover(true);
            foreach (string name in new[] { "NuvioVpn.exe", "wireguard.exe", "wg.exe" }) {
                string target = Path.Combine(Program.Root, name); Program.EnsureNoReparse(target);
                if (File.Exists(target)) File.Delete(target);
            }
            // Shared drivers and unrelated VPNs are deliberately outside this lifecycle.
        }
        static void Cleanup() {
            Program.EnsureNoReparse(Program.Root); Program.EnsureNoReparse(Backup);
            if (!Directory.Exists(Backup)) return;
            foreach (string name in Files) {
                string target = Path.Combine(Backup, name); Program.EnsureNoReparse(target);
                if (File.Exists(target)) File.Delete(target);
            }
            string marker = Path.Combine(Backup, "prepared");
            Program.EnsureNoReparse(marker); if (File.Exists(marker)) File.Delete(marker);
            DeleteEmpty(Path.Combine(Backup, "State")); DeleteEmpty(Backup);
            DeleteEmpty(Program.State); DeleteEmpty(Program.Root);
        }
        static void DeleteEmpty(string path) {
            Program.EnsureNoReparse(path);
            if (Directory.Exists(path) && Directory.GetFileSystemEntries(path).Length == 0) Directory.Delete(path);
        }
    }
}
