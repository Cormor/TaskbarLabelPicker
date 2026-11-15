using System;
using System.Diagnostics;
using System.IO;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Launcher
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string RunName = "Lixing.TaskbarLabelPicker";
    const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Lixing.TaskbarLabelPicker";
    const string InstallMutex = @"Local\Lixing.TaskbarLabelPicker.Launcher";
    static readonly string Root = AppDomain.CurrentDomain.BaseDirectory;
    static readonly string App = Path.Combine(Root, @"app\TaskbarLabelPicker.exe");
    static readonly string Host = Path.Combine(Root, @"runtime\Windhawk\windhawk.exe");
    static readonly string Startup = Quote(Path.Combine(Root, "TaskbarLabelPickerLauncher.exe")) + " --tray";

    [STAThread]
    static int Main(string[] args)
    {
        string action = args.Length == 0 ? "" : args[0];
        if (args.Length > 1) return 2;
        try
        {
            if (action.StartsWith("--autostart-", StringComparison.Ordinal))
                return Autostart(action);

            using (var mutex = new Mutex(false, InstallMutex))
            {
                bool held;
                try { held = mutex.WaitOne(TimeSpan.FromSeconds(25)); }
                catch (AbandonedMutexException) { held = true; }
                if (!held) throw new IOException("另一项任务栏标签操作尚未结束。");
                try
                {
                    if (action == "--stop") return Stop();
                    if (action != "" && action != "--tray") return 2;
                    if (!File.Exists(App) || !File.Exists(Host))
                        throw new FileNotFoundException("应用组件缺失，请重新运行安装程序。");
                    if (!ProfileLocation.IsReady && (IsRunning(App) || IsRunning(Host)))
                        throw new IOException("请退出旧版任务栏标签，再启动新版以迁移设置。");
                    ProfileLocation.Prepare();
                    if (!IsRunning(Host))
                    {
                        var daemons = Process.GetProcessesByName("windhawk");
                        bool anotherHost = daemons.Length > 0;
                        foreach (var daemon in daemons) daemon.Dispose();
                        if (anotherHost)
                            throw new IOException("另一个 Windhawk 实例正在运行。请先退出该实例，再打开任务栏标签。");
                        Launch(Host, "-tray-only").Dispose();
                    }
                    Launch(App, action).Dispose();
                    return 0;
                }
                finally { mutex.ReleaseMutex(); }
            }
        }
        catch (Exception failure)
        {
            if (action == "")
                MessageBox.Show(failure.Message, "任务栏标签", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    static string Quote(string path) { return "\"" + path + "\""; }

    static int Autostart(string action)
    {
        if (action != "--autostart-status" && action != "--autostart-enable" &&
            action != "--autostart-disable") return 2;

        if (!IsRegisteredInstallation())
        {
            ReportAutostart("unavailable");
            return action == "--autostart-status" ? 0 : 4;
        }

        string state;
        bool enable = action == "--autostart-enable";
        using (var key = enable ? Registry.CurrentUser.CreateSubKey(RunKey)
                                : Registry.CurrentUser.OpenSubKey(RunKey, action != "--autostart-status"))
        {
            var value = key == null ? null : key.GetValue(RunName, null, RegistryValueOptions.DoNotExpandEnvironmentNames);
            bool owned = String.Equals(value as string, Startup, StringComparison.OrdinalIgnoreCase);
            if (value != null && !owned)
                state = "foreign";
            else if (action == "--autostart-enable" && !owned)
            {
                key.SetValue(RunName, Startup, RegistryValueKind.String);
                state = "enabled";
            }
            else if (action == "--autostart-disable" && owned)
            {
                key.DeleteValue(RunName, false);
                state = "disabled";
            }
            else
                state = owned ? "enabled" : "disabled";
        }
        ReportAutostart(state);
        return state == "foreign" && action != "--autostart-status" ? 3 : 0;
    }

    static bool IsRegisteredInstallation()
    {
        using (var key = Registry.CurrentUser.OpenSubKey(UninstallKey))
        {
            if (key == null) return false;
            var registered = key.GetValue("InstallLocation") as string;
            if (String.IsNullOrEmpty(registered) ||
                !String.Equals(Path.GetFullPath(registered).TrimEnd('\\'),
                               Path.GetFullPath(Root).TrimEnd('\\'),
                               StringComparison.OrdinalIgnoreCase))
                return false;
            string uninstaller = Path.Combine(Root, "Uninstall.exe");
            return File.Exists(uninstaller) &&
                String.Equals(key.GetValue("UninstallString") as string, Quote(uninstaller),
                              StringComparison.OrdinalIgnoreCase);
        }
    }

    static void ReportAutostart(string state)
    {
        using (var stream = Console.OpenStandardOutput())
        using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
            try { writer.WriteLine(state); writer.Flush(); }
            catch (IOException) { /* Explorer launches without an output stream. */ }
    }

    static Process Launch(string executable, string arguments)
    {
        return Process.Start(new ProcessStartInfo(executable, arguments) {
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(executable)
        });
    }

    static List<Process> FindRunning(string executable)
    {
        var matching = new List<Process>();
        foreach (var process in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(executable)))
        {
            try
            {
                if (String.Equals(process.MainModule.FileName, executable, StringComparison.OrdinalIgnoreCase))
                {
                    matching.Add(process);
                    continue;
                }
            }
            catch (System.ComponentModel.Win32Exception) { }
            catch (InvalidOperationException) { }
            process.Dispose();
        }
        return matching;
    }

    static bool IsRunning(string executable)
    {
        var matching = FindRunning(executable);
        try { return matching.Count > 0; }
        finally { foreach (var process in matching) process.Dispose(); }
    }

    static bool Stop(string executable, string arguments)
    {
        var matching = FindRunning(executable);
        try
        {
            if (matching.Count == 0) return true;
            using (var request = Launch(executable, arguments))
            {
                if (!request.WaitForExit(12000) || request.ExitCode != 0) return false;
            }
            var remaining = Stopwatch.StartNew();
            foreach (var process in matching)
            {
                int timeout = Math.Max(0, 10000 - (int)remaining.ElapsedMilliseconds);
                if (!process.WaitForExit(timeout)) return false;
            }
            return !IsRunning(executable);
        }
        finally { foreach (var process in matching) process.Dispose(); }
    }

    static int Stop()
    {
        if (!Stop(App, "--quit")) return 1;
        return Stop(Host, "-exit -wait -timeout 10000") ? 0 : 1;
    }
}
