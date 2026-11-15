using System;
using System.IO;

internal static class ProfileLocation
{
    const string ReadyFile = "profile.ready";
    static readonly string UserProfile =
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
    internal static readonly string DirectoryPath =
        Path.Combine(UserProfile, ".lixing", "TaskbarLabelPicker");

    internal static bool IsReady
    {
        get { return File.Exists(Path.Combine(DirectoryPath, ReadyFile)); }
    }

    internal static void Prepare()
    {
        if (IsReady) return;
        if (String.IsNullOrEmpty(UserProfile))
            throw new IOException("无法确定当前用户的配置文件目录。");
        if (Directory.Exists(DirectoryPath))
            throw new IOException("配置目录中已有未完成的数据。旧设置已保留，请检查目录后重新启动。");

        var legacy = Path.Combine(UserProfile, ".taskbar-label-picker");
        var parent = Path.GetDirectoryName(DirectoryPath);
        Directory.CreateDirectory(parent);
        var staging = Path.Combine(parent, ".taskbar-label-picker-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        try
        {
            // The calling launcher holds its mutex and the old app and host have stopped.
            foreach (var name in new[] { "rules.dat", "auto-new.dat", "presence.dat", "pending-cleanup.dat" })
                CopyIfPresent(legacy, staging, name);

            var oldHistory = Path.Combine(legacy, "history");
            if (Directory.Exists(oldHistory))
            {
                var newHistory = Path.Combine(staging, "history");
                Directory.CreateDirectory(newHistory);
                foreach (var path in Directory.EnumerateFiles(oldHistory, "*.entry"))
                    CopyIfPresent(oldHistory, newHistory, Path.GetFileName(path));
            }
            File.WriteAllText(Path.Combine(staging, ReadyFile), "1");
            Directory.Move(staging, DirectoryPath);
        }
        finally
        {
            if (Directory.Exists(staging)) Directory.Delete(staging, true);
        }
    }

    static void CopyIfPresent(string source, string destination, string name)
    {
        var file = Path.Combine(source, name);
        if (File.Exists(file)) File.Copy(file, Path.Combine(destination, name));
    }
}
