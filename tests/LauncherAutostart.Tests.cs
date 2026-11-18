using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

internal static class LauncherAutostartTests
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Lixing.TaskbarLabelPicker";
    const string RunName = "Lixing.TaskbarLabelPicker";
    static readonly IntPtr CurrentUser = new IntPtr(unchecked((int)0x80000001));

    static int Main(string[] args)
    {
        try { return Run(args); }
        catch (Exception failure)
        {
            Console.Error.WriteLine(failure.Message);
            return 1;
        }
    }

    static int Run(string[] args)
    {
        string root = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        string fixture = @"Software\Lixing\TaskbarLabelPicker-autostart-test-" + Guid.NewGuid().ToString("N");
        using (var rootKey = Registry.CurrentUser.CreateSubKey(fixture))
        {
            Check(RegOverridePredefKey(CurrentUser, rootKey.Handle.DangerousGetHandle()) == 0, "Unable to isolate registry");
            try
            {
                Check(Invoke("--autostart-enable") == 4, "An unregistered copy was allowed to enable startup");
                using (var registration = Registry.CurrentUser.CreateSubKey(UninstallKey))
                    registration.SetValue("InstallLocation", root);

                Check(!File.Exists(Path.Combine(root, "Uninstall.exe")), "Fixture must have no uninstaller");
                Check(Launcher.InstallationIssue(root, root) == null, "Installed launcher rejected without uninstaller");
                Check(Launcher.InstallationIssue(root, args[0]) == "different-installation", "Another directory was accepted");
                Check(Launcher.InstallationIssue(root, args[1]) == null, "Alias of the installed directory was rejected");

                const string approved = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
                byte[] existingWindowsChoice = { 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
                using (var approval = Registry.CurrentUser.CreateSubKey(approved))
                    approval.SetValue(RunName, existingWindowsChoice, RegistryValueKind.Binary);

                string expected = "\"" + Path.Combine(root, "TaskbarLabelPickerLauncher.exe") + "\" --tray";
                Check(Invoke("--autostart-enable") == 0 && ReadRun() == expected, "Missing uninstall data blocked the Run setting");
                const string malformedUninstall = "a stale uninstall command";
                using (var registration = Registry.CurrentUser.OpenSubKey(UninstallKey, true))
                    registration.SetValue("UninstallString", malformedUninstall);
                Check(Invoke("--autostart-status") == 0 && ReadRun() == expected, "Read changed the startup choice");
                Check(Invoke("--autostart-disable") == 0 && ReadRun() == null, "Disable did not remove the app registration");

                using (var approval = Registry.CurrentUser.OpenSubKey(approved))
                    Check(Convert.ToBase64String((byte[])approval.GetValue(RunName)) == Convert.ToBase64String(existingWindowsChoice),
                        "App settings changed the separate Windows startup approval");
                using (var run = Registry.CurrentUser.OpenSubKey(RunKey, true))
                    run.SetValue(RunName, "foreign startup command");
                Check(Invoke("--autostart-enable") == 3 && ReadRun() == "foreign startup command", "Enable replaced a foreign startup entry");
                Check(Invoke("--autostart-disable") == 3 && ReadRun() == "foreign startup command", "Disable deleted a foreign startup entry");
                Console.WriteLine("Launcher autostart regression passed");
                return 0;
            }
            finally
            {
                Check(RegOverridePredefKey(CurrentUser, IntPtr.Zero) == 0, "Unable to restore registry mapping");
                Registry.CurrentUser.DeleteSubKeyTree(fixture);
            }
        }
    }

    static string ReadRun()
    {
        using (var key = Registry.CurrentUser.OpenSubKey(RunKey))
            return key == null ? null : key.GetValue(RunName) as string;
    }

    static int Invoke(string argument)
    {
        var method = typeof(Launcher).GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic);
        return (int)method.Invoke(null, new object[] { new[] { argument } });
    }

    static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    [DllImport("advapi32.dll")]
    static extern int RegOverridePredefKey(IntPtr key, IntPtr replacement);
}
