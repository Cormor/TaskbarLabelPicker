using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;
using Microsoft.Win32;

internal static class TaskbarBridge
{
    const string ConfigKey = @"Software\Lixing\TaskbarLabelPicker";
    const string LiveProperty = "Lixing.TaskbarLabelPicker.Active.1";
    const string ButtonClass = "Taskbar.TaskListButtonAutomationPeer";
    const string WindowPrefix = "Window: 0x";
    const string AppPrefix = "Appid: ";
    static readonly StringComparer IdentityComparer = StringComparer.OrdinalIgnoreCase;
    static readonly Encoding Utf8 = new UTF8Encoding(false);
    static readonly string ProfileDirectory = ProfileLocation.DirectoryPath;
    static readonly string RulesFile = Path.Combine(ProfileDirectory, "rules.dat");
    static readonly string AppliedFile = Path.Combine(ProfileDirectory, "applied.dat");
    static readonly string AliasesFile = Path.Combine(ProfileDirectory, "aliases.dat");
    const int MaximumApps = 500;
    static readonly string PresenceFile = Path.Combine(ProfileDirectory, "presence.dat");
    static readonly string PendingCleanupFile = Path.Combine(ProfileDirectory, "pending-cleanup.dat");
    static readonly string AutoNewFile = Path.Combine(ProfileDirectory, "auto-new.dat");
    const string ShowPrefix = "show:";
    const string StackPrefix = "stack:";
    const string SeparatePrefix = "separate:";
    const string AutoStackPrefix = "auto-stack:";
    static StreamWriter output;
    static Dictionary<string, IconSample> icons = new Dictionary<string, IconSample>(IdentityComparer);
    static readonly Dictionary<string, HistoryApp> history = new Dictionary<string, HistoryApp>(IdentityComparer);
    static List<VisibleItem> lastVisibleItems;
    static bool historyLoaded;
    static string observedAppearanceRevision;
    static readonly Dictionary<string, DateTime> historyWriteTimes = new Dictionary<string, DateTime>(IdentityComparer);
    static long historyRevision;

    sealed class IconSample
    {
        public string Image;
        public long SampledAt;
    }

    sealed class VisibleItem : IEquatable<VisibleItem>
    {
        public string Title, Identity, ProcessKey, Icon, Instance;
        public int Windows;
        public string LiveIcon, LiveStack;
        public double Width;

        public bool Equals(VisibleItem other)
        {
            return other != null && Title == other.Title && Identity == other.Identity &&
                ProcessKey == other.ProcessKey && Icon == other.Icon && Instance == other.Instance &&
                Windows == other.Windows && Width.Equals(other.Width) &&
                LiveIcon == other.LiveIcon && LiveStack == other.LiveStack;
        }

        public void Write()
        {
            Emit("ITEM", Encode(Title), Encode(Identity), Encode(ProcessKey), Icon,
                Width.ToString("0", CultureInfo.InvariantCulture),
                Windows.ToString(CultureInfo.InvariantCulture), Encode(Instance), LiveIcon, LiveStack);
        }
    }

    sealed class HistoryApp
    {
        public string Title, Identity, ProcessKey, Icon, File;
        public long LastSeen;
        public HashSet<string> Windows;
    }

    static readonly long IconRefreshInterval = Stopwatch.Frequency * 5;

    [STAThread]
    public static void Main(string[] args)
    {
        if (!ProfileLocation.IsReady)
        {
            using (var error = new StreamWriter(Console.OpenStandardError(), Utf8))
                error.WriteLine("Taskbar profile is not ready. Open the installed launcher.");
            Environment.ExitCode = 1;
            return;
        }

        if (args.Length == 2 && args[0] == "tray-menu")
        {
            SetProcessDpiAwarenessContext(new IntPtr(-4));
            using (var result = new StreamWriter(Console.OpenStandardOutput(), Utf8))
            {
                uint selected = WindowsTrayMenu.Show(args[1]);
                if (selected == 1) result.WriteLine("open");
                else if (selected == 3) result.WriteLine("exit");
            }
            return;
        }
        if (args.Length == 1 && args[0] == "events")
        {
            var notifier = new Thread(WatchEvents);
            notifier.SetApartmentState(ApartmentState.MTA);
            notifier.Start();
            notifier.Join();
            return;
        }

        var input = new StreamReader(Console.OpenStandardInput(), Utf8);
        output = new StreamWriter(Console.OpenStandardOutput(), Utf8);
        SetProcessDpiAwarenessContext(new IntPtr(-4));
        string command;
        while ((command = input.ReadLine()) != null)
        {
            try
            {
                if (command == "scan" || command == "scan-view" || command == "scan-hidden")
                    Scan(command);
                else if (command == "history")
                    EmitHistory();
                else if (command == "status")
                {
                    EmitStatus();
                    Emit("HREV", historyRevision.ToString(CultureInfo.InvariantCulture));
                }
                else
                {
                    string[] fields = command.Split('\t');
                    if (fields.Length == 2 && fields[0] == "set-auto-new" && (fields[1] == "0" || fields[1] == "1"))
                    {
                        Emit("AUTO_NEW", SetAutoNew(fields[1] == "1") ? "1" : "0");
                        continue;
                    }
                    if (fields[0] == "remove-apps" && fields.Length > 1 && fields.Length <= MaximumApps + 1)
                    {
                        var keys = fields.Skip(1).Select(Decode).Distinct(IdentityComparer).ToArray();
                        if (keys.Any(key => !IsStableKey(key))) throw new ArgumentException("无法识别待清理的应用");
                        int count;
                        int removed = RemoveApplications(keys, out count);
                        Emit("REMOVED", removed.ToString(CultureInfo.InvariantCulture));
                        Emit("APP_COUNT", count.ToString(CultureInfo.InvariantCulture));
                        continue;
                    }
                    bool extendedIcon = (fields.Length == 4 || fields.Length == 5) && fields[0] == "set-icon";
                    if ((fields.Length != 3 && !extendedIcon) || (fields[2] != "0" && fields[2] != "1"))
                        throw new ArgumentException("Unknown command");
                    string identity = Decode(fields[1]);
                    bool enabled = fields[2] == "1";
                    switch (fields[0])
                    {
                        case "set": SetIcon(identity, enabled, null, ""); break;
                        case "set-icon":
                            SetIcon(identity, enabled, fields.Length == 5 ? Decode(fields[4]) : identity,
                                fields.Length >= 4 ? Decode(fields[3]) : "");
                            break;
                        case "set-stack": SetStack(identity, enabled); break;
                        default: throw new ArgumentException("Unknown command");
                    }
                }
            }
            catch (Exception ex)
            {
                Emit("ERROR", Encode(ex.Message));
            }
            finally
            {
                Emit("END");
                output.Flush();
            }
        }
    }

    const uint TaskbarSignal = 0x8001;
    const uint SettingsSignal = 0x8002;
    const uint RebindSignal = 0x8003;
    static uint eventThread;
    static int taskbarPending, settingsPending, rebindPending;
    static readonly Dictionary<IntPtr, AutomationElement> subscriptions = new Dictionary<IntPtr, AutomationElement>();
    static readonly StructureChangedEventHandler StructureChangedHandler = OnTaskbarStructureChanged;
    static readonly AutomationPropertyChangedEventHandler PropertyChangedHandler = OnTaskbarPropertyChanged;

    static bool IsTaskbarWindow(IntPtr window)
    {
        var name = new StringBuilder(64);
        GetClassName(window, name, name.Capacity);
        string windowClass = name.ToString();
        return windowClass == "Shell_TrayWnd" || windowClass == "Shell_SecondaryTrayWnd";
    }

    static void Signal(uint message, ref int pending)
    {
        if (Interlocked.Exchange(ref pending, 1) == 0 &&
            !PostThreadMessage(eventThread, message, IntPtr.Zero, IntPtr.Zero))
            Interlocked.Exchange(ref pending, 0);
    }

    static void OnTaskbarStructureChanged(object sender, StructureChangedEventArgs args)
    {
        if (args.StructureChangeType == StructureChangeType.ChildAdded)
            RecordVisibleButton(sender as AutomationElement);
        Signal(TaskbarSignal, ref taskbarPending);
    }

    static void OnTaskbarPropertyChanged(object sender, AutomationPropertyChangedEventArgs args)
    {
        var item = sender as AutomationElement;
        if (item == null) return;
        try
        {
            string itemClass;
            try { itemClass = item.Cached.ClassName; }
            catch (InvalidOperationException) { itemClass = item.Current.ClassName; }
            if (itemClass == ButtonClass)
            {
                if (args.Property == AutomationElement.IsOffscreenProperty && Object.Equals(args.NewValue, false))
                    RecordVisibleButton(item);
                Signal(TaskbarSignal, ref taskbarPending);
            }
        }
        catch (ElementNotAvailableException) { }
    }

    static void RebindTaskbarEvents()
    {
        var current = new HashSet<IntPtr>();
        EnumWindows((window, unused) =>
        {
            if (IsTaskbarWindow(window)) current.Add(window);
            return true;
        }, IntPtr.Zero);

        foreach (IntPtr removed in subscriptions.Keys.Where(window => !current.Contains(window)).ToArray())
        {
            AutomationElement root = subscriptions[removed];
            try { Automation.RemoveStructureChangedEventHandler(root, StructureChangedHandler); }
            catch (ElementNotAvailableException) { }
            try { Automation.RemoveAutomationPropertyChangedEventHandler(root, PropertyChangedHandler); }
            catch (ElementNotAvailableException) { }
            subscriptions.Remove(removed);
        }

        var cache = new CacheRequest();
        cache.Add(AutomationElement.ClassNameProperty);
        cache.Add(AutomationElement.AutomationIdProperty);
        cache.Add(AutomationElement.IsOffscreenProperty);
        foreach (IntPtr window in current.Where(window => !subscriptions.ContainsKey(window)))
        {
            try
            {
                var root = AutomationElement.FromHandle(window);
                using (cache.Activate())
                {
                    Automation.AddStructureChangedEventHandler(root, TreeScope.Subtree, StructureChangedHandler);
                    Automation.AddAutomationPropertyChangedEventHandler(root, TreeScope.Subtree,
                        PropertyChangedHandler, AutomationElement.NameProperty, AutomationElement.IsOffscreenProperty);
                }
                subscriptions.Add(window, root);
            }
            catch (ElementNotAvailableException) { }
        }
    }

    static void HandleStatePath(string name)
    {
        if (IdentityComparer.Equals(name, "aliases.dat"))
            Signal(TaskbarSignal, ref taskbarPending);
        else if (IdentityComparer.Equals(name, "rules.dat") || IdentityComparer.Equals(name, "applied.dat") ||
                 IdentityComparer.Equals(name, "auto-new.dat") ||
                 IdentityComparer.Equals(name, "history-revision.dat"))
            Signal(SettingsSignal, ref settingsPending);
    }

    static void WatchEvents()
    {
        output = new StreamWriter(Console.OpenStandardOutput(), Utf8) { AutoFlush = true };
        eventThread = GetCurrentThreadId();
        Message message;
        PeekMessage(out message, IntPtr.Zero, 0, 0, 0); // Initialize the WinEvent thread's message queue.
        var parent = new Thread(() =>
        {
            using (var input = Console.OpenStandardInput())
                while (input.ReadByte() != -1) { }
            PostThreadMessage(eventThread, 0x0012, IntPtr.Zero, IntPtr.Zero); // WM_QUIT
        }) { IsBackground = true };
        parent.Start();
        WinEventProc onWindow = (hook, kind, window, objectId, childId, thread, time) =>
        {
            if (window != IntPtr.Zero && objectId == 0 && childId == 0 &&
                (IsTaskbarWindow(window) || subscriptions.ContainsKey(window)))
                Signal(RebindSignal, ref rebindPending);
        };
        IntPtr hookHandle = SetWinEventHook(0x8000, 0x8003, IntPtr.Zero, onWindow, 0, 0, 2);
        Directory.CreateDirectory(ProfileDirectory);
        using (var watcher = new FileSystemWatcher(ProfileDirectory))
        {
            watcher.NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite;
            watcher.Created += (sender, args) => HandleStatePath(args.Name);
            watcher.Changed += (sender, args) => HandleStatePath(args.Name);
            watcher.Deleted += (sender, args) => HandleStatePath(args.Name);
            watcher.Renamed += (sender, args) =>
            {
                HandleStatePath(args.OldName);
                HandleStatePath(args.Name);
            };
            watcher.Error += (sender, args) =>
            {
                Signal(TaskbarSignal, ref taskbarPending);
                Signal(SettingsSignal, ref settingsPending);
            };
            watcher.EnableRaisingEvents = true;
            RebindTaskbarEvents();
            Emit("READY");
            while (GetMessage(out message, IntPtr.Zero, 0, 0) > 0)
            {
                switch (message.Id)
                {
                    case RebindSignal:
                        Interlocked.Exchange(ref rebindPending, 0);
                        RebindTaskbarEvents();
                        Signal(TaskbarSignal, ref taskbarPending);
                        Signal(SettingsSignal, ref settingsPending);
                        break;
                    case TaskbarSignal:
                        Interlocked.Exchange(ref taskbarPending, 0);
                        Emit("EVENT", "taskbar");
                        break;
                    case SettingsSignal:
                        Interlocked.Exchange(ref settingsPending, 0);
                        Emit("EVENT", "state");
                        break;
                }
            }
        }
        if (hookHandle != IntPtr.Zero) UnhookWinEvent(hookHandle);
        Automation.RemoveAllEventHandlers();
        GC.KeepAlive(onWindow);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct Message
    {
        public IntPtr Window;
        public uint Id;
        public IntPtr WParam, LParam;
        public uint Time;
        public int X, Y;
    }

    delegate void WinEventProc(IntPtr hook, uint kind, IntPtr window, int objectId, int childId, uint thread, uint time);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool PostThreadMessage(uint thread, uint id, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetMessage(out Message value, IntPtr window, uint minimum, uint maximum);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool PeekMessage(out Message value, IntPtr window, uint minimum, uint maximum, uint remove);
    [DllImport("user32.dll")] static extern IntPtr SetWinEventHook(uint minimum, uint maximum, IntPtr module, WinEventProc callback, uint process, uint thread, uint flags);
    [DllImport("user32.dll")] static extern bool UnhookWinEvent(IntPtr hook);

    static void Emit(params string[] fields)
    {
        output.WriteLine(String.Join("\t", fields));
    }

    static string Encode(string text)
    {
        return Convert.ToBase64String(Utf8.GetBytes(text ?? ""));
    }

    static string Decode(string token)
    {
        return Utf8.GetString(Convert.FromBase64String(token));
    }

    static List<string> ReadRules(RegistryKey config, string name)
    {
        if (config == null) return new List<string>();
        var values = config.GetValue(name) as string[];
        return values == null ? new List<string>() : values.Where(IsKey).ToList();
    }

    static T WithRulesLock<T>(Func<T> action)
    {
        using (var mutex = new Mutex(false, @"Local\Lixing.TaskbarLabelPicker.Settings"))
        {
            try { mutex.WaitOne(); } catch (AbandonedMutexException) { }
            try { return action(); }
            finally { mutex.ReleaseMutex(); }
        }
    }

    static List<string> ReadProfile(string profile)
    {
        if (!File.Exists(profile)) return new List<string>();
        using (var file = new FileStream(profile, FileMode.Open, FileAccess.Read,
                                         FileShare.ReadWrite | FileShare.Delete))
        using (var reader = new StreamReader(file, Encoding.Unicode))
            return reader.ReadToEnd().Split('\0').Where(IsRuleEntry).ToList();
    }

    static void SyncProfile(List<string> rules)
    {
        if (File.Exists(RulesFile) && ReadProfile(RulesFile).SequenceEqual(rules, IdentityComparer))
            return;
        Directory.CreateDirectory(ProfileDirectory);
        string temporary = RulesFile + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, Encoding.Unicode.GetBytes(String.Join("\0", rules)));
            if (File.Exists(RulesFile)) File.Replace(temporary, RulesFile, null);
            else File.Move(temporary, RulesFile);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    static List<string> LoadRules()
    {
        if (File.Exists(RulesFile)) return ReadProfile(RulesFile);
        // Import the first preview's settings if one was used in this profile.
        using (var config = Registry.CurrentUser.OpenSubKey(ConfigKey))
        {
            var rules = ReadRules(config, "Rules");
            SyncProfile(rules);
            return rules;
        }
    }

    static T WithHistoryLock<T>(Func<T> action)
    {
        using (var mutex = new Mutex(false, @"Local\Lixing.TaskbarLabelPicker.History"))
        {
            try { mutex.WaitOne(); } catch (AbandonedMutexException) { }
            try { return action(); }
            finally { mutex.ReleaseMutex(); }
        }
    }

    static void EditRule(string key, Action<List<string>> edit)
    {
        if (!IsStableKey(key)) throw new ArgumentException("Unknown taskbar identity");
        WithHistoryLock(() => WithRulesLock(() =>
        {
            LoadHistory();
            FinishPendingCleanup(LoadRules());
            var rules = LoadRules();
            edit(rules);
            var data = ReadPresence();
            TrimApplications(rules, data, key);
            SyncProfile(rules);
            return true;
        }));
    }

    static bool GetAutoNew()
    {
        try
        {
            using (var file = new FileStream(AutoNewFile, FileMode.Open, FileAccess.Read,
                                             FileShare.ReadWrite | FileShare.Delete))
                return file.ReadByte() == '1';
        }
        catch (FileNotFoundException) { return false; }
        catch (DirectoryNotFoundException) { return false; }
    }

    static bool SetAutoNew(bool enabled)
    {
        return WithRulesLock(() =>
        {
            if (GetAutoNew() == enabled) return enabled;
            Directory.CreateDirectory(ProfileDirectory);
            string temporary = AutoNewFile + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                File.WriteAllBytes(temporary, new[] { (byte)(enabled ? '1' : '0') });
                if (File.Exists(AutoNewFile)) File.Replace(temporary, AutoNewFile, null);
                else File.Move(temporary, AutoNewFile);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            return enabled;
        });
    }

    static bool HasExplicitRule(List<string> rules, string key)
    {
        return !String.IsNullOrEmpty(key) && rules.Any(value =>
            IdentityComparer.Equals(value, key) || IdentityComparer.Equals(value, ShowPrefix + key) ||
            IsStackRuleFor(value, key));
    }

    static bool HasHistoryAlias(HistoryApp app)
    {
        if (String.IsNullOrEmpty(app.ProcessKey)) return false;
        if (!IdentityComparer.Equals(app.Identity, app.ProcessKey))
            return File.Exists(HistoryFile(app.ProcessKey));
        // A temporary path-only observation of an application previously
        // known by AppID is also not a new application.
        foreach (var path in Directory.EnumerateFiles(HistoryDirectory, "*.entry"))
        {
            var prior = ReadHistoryEntry(path);
            if (prior != null && IdentityComparer.Equals(prior.ProcessKey, app.Identity))
                return true;
        }
        return false;
    }

    static void ApplyFirstAppearanceRule(HistoryApp app)
    {
        if (!GetAutoNew() || HasHistoryAlias(app)) return;

        WithRulesLock(() =>
        {
            if (!GetAutoNew()) return false;
            var rules = LoadRules();
            if (HasExplicitRule(rules, app.Identity) || HasExplicitRule(rules, app.ProcessKey))
                return false;
            rules.Add(app.Identity);
            rules.Add(StackPrefix + app.Identity);
            rules.Add(AutoStackPrefix + Encode(app.Identity) + ":" + Encode(app.Identity) + ":-");
            SyncProfile(rules);
            return true;
        });
    }

    sealed class AutoStack
    {
        public string IconKey, StackKey;
        public bool RestoreSeparate;
    }

    static AutoStack DecodeAutoStack(string rule)
    {
        if (!rule.StartsWith(AutoStackPrefix, StringComparison.Ordinal)) return null;
        var fields = rule.Substring(AutoStackPrefix.Length).Split(':');
        if (fields.Length != 3 || (fields[2] != "0" && fields[2] != "-")) return null;
        try
        {
            var icon = Decode(fields[0]);
            var stack = Decode(fields[1]);
            if (IsKey(icon) && IsKey(stack))
                return new AutoStack { IconKey = icon, StackKey = stack, RestoreSeparate = fields[2] == "0" };
        }
        catch (FormatException) { }
        return null;
    }

    static bool? GetStack(List<string> rules, string key)
    {
        if (rules.Any(value => IdentityComparer.Equals(value, SeparatePrefix + key))) return false;
        if (rules.Any(value => IdentityComparer.Equals(value, StackPrefix + key))) return true;
        return null;
    }

    static void SetIcon(string key, bool hidden, string stackKey, string processAlias)
    {
        if (stackKey != null && !IsKey(stackKey))
            throw new ArgumentException("Unknown taskbar identity");
        EditRule(key, rules =>
        {
            if (hidden && rules.Any(value => IdentityComparer.Equals(value, key))) return;
            rules.RemoveAll(value => IdentityComparer.Equals(value, key) ||
                IdentityComparer.Equals(value, ShowPrefix + key));
            if (!hidden)
            {
                if (stackKey != null) rules.Add(ShowPrefix + key);
                foreach (string marker in rules.Where(value => value.StartsWith(AutoStackPrefix, StringComparison.Ordinal)).ToArray())
                {
                    var auto = DecodeAutoStack(marker);
                    if (auto == null || !IdentityComparer.Equals(auto.IconKey, key)) continue;
                    rules.Remove(marker);
                    if (GetStack(rules, auto.StackKey) == true)
                    {
                        rules.RemoveAll(value => IsStackRuleFor(value, auto.StackKey));
                        if (auto.RestoreSeparate) rules.Add(SeparatePrefix + auto.StackKey);
                    }
                }
                return;
            }
            rules.Add(key);
            if (stackKey == null) return;
            var previous = GetStack(rules, stackKey);
            var effective = previous ?? (IsKey(processAlias) ? GetStack(rules, processAlias) : null);
            if (effective == true) return;

            rules.RemoveAll(value => IsStackRuleFor(value, stackKey));
            rules.Add(StackPrefix + stackKey);
            rules.Add(AutoStackPrefix + Encode(key) + ":" + Encode(stackKey) + ":" + (previous == false ? "0" : "-"));
        });
    }

    static void SetStack(string key, bool stack)
    {
        EditRule(key, rules =>
        {
            rules.RemoveAll(value =>
            {
                var auto = DecodeAutoStack(value);
                return IsStackRuleFor(value, key) ||
                    (auto != null && IdentityComparer.Equals(auto.StackKey, key));
            });
            rules.Add((stack ? StackPrefix : SeparatePrefix) + key);
        });
    }

    static bool IsStackRuleFor(string entry, string key)
    {
        return IdentityComparer.Equals(entry, StackPrefix + key) ||
            IdentityComparer.Equals(entry, SeparatePrefix + key);
    }

    static bool IsStableKey(string value)
    {
        return IsKey(value) && !value.StartsWith("id:*PID", StringComparison.OrdinalIgnoreCase);
    }

    static bool IsKey(string value)
    {
        return value != null && value.IndexOf('\0') == -1 &&
            ((value.Length > 3 && value.StartsWith("id:", StringComparison.Ordinal)) ||
             (value.Length > 5 && value.StartsWith("path:", StringComparison.Ordinal)));
    }

    static bool IsRuleEntry(string value)
    {
        if (value.StartsWith(AutoStackPrefix, StringComparison.Ordinal))
            return DecodeAutoStack(value) != null;
        if (value.StartsWith(ShowPrefix, StringComparison.Ordinal))
            return IsKey(value.Substring(ShowPrefix.Length));
        if (value.StartsWith(StackPrefix, StringComparison.Ordinal))
            return IsKey(value.Substring(StackPrefix.Length));
        if (value.StartsWith(SeparatePrefix, StringComparison.Ordinal))
            return IsKey(value.Substring(SeparatePrefix.Length));
        return IsKey(value);
    }

    sealed class Alias
    {
        public string ProcessKey;
        public int WindowCount;
    }

    static Dictionary<string, Alias> ReadAliases()
    {
        var result = new Dictionary<string, Alias>(IdentityComparer);
        if (!File.Exists(AliasesFile)) return result;
        using (var file = new FileStream(AliasesFile, FileMode.Open, FileAccess.Read,
                                        FileShare.ReadWrite | FileShare.Delete))
        using (var reader = new StreamReader(file, Encoding.Unicode))
        {
            var entries = reader.ReadToEnd().Split('\0');
            for (int i = 0; i + 2 < entries.Length; i += 3)
            {
                int count;
                if ((entries[i].StartsWith("id:", StringComparison.Ordinal) ||
                     entries[i].StartsWith("window:", StringComparison.Ordinal) ||
                     entries[i].StartsWith("layout:", StringComparison.Ordinal)) &&
                    Int32.TryParse(entries[i + 2], NumberStyles.None, CultureInfo.InvariantCulture, out count))
                    result[entries[i]] = new Alias { ProcessKey = entries[i + 1], WindowCount = count };
            }
        }
        return result;
    }

    static string HistoryDirectory { get { return Path.Combine(ProfileDirectory, "history"); } }

    static string AppearanceRevisionFile { get { return Path.Combine(ProfileDirectory, "history-revision.dat"); } }

    static void WriteProfileText(string path, string contents)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, contents, Utf8);
            if (File.Exists(path)) File.Replace(temporary, path, null);
            else File.Move(temporary, path);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    static void MarkHistoryChanged()
    {
        WriteProfileText(AppearanceRevisionFile, Guid.NewGuid().ToString("N"));
    }

    static string ReadAppearanceRevision()
    {
        try
        {
            using (var file = new FileStream(AppearanceRevisionFile, FileMode.Open, FileAccess.Read,
                                            FileShare.ReadWrite | FileShare.Delete))
            using (var reader = new StreamReader(file, Utf8))
                return reader.ReadToEnd();
        }
        catch (FileNotFoundException) { return ""; }
        catch (DirectoryNotFoundException) { return ""; }
    }

    static void LoadHistory()
    {
        // The notifier completes the entry and revision before sending its
        // taskbar event. The reader can deterministically merge that entry
        // without depending on a second asynchronous file watcher callback.
        string revision = ReadAppearanceRevision();
        if (historyLoaded && revision == observedAppearanceRevision) return;
        foreach (var missing in history.Where(pair => !File.Exists(pair.Value.File)).Select(pair => pair.Key).ToArray())
        {
            historyWriteTimes.Remove(history[missing].File);
            history.Remove(missing);
            historyRevision++;
        }
        if (Directory.Exists(HistoryDirectory))
        {
            foreach (var file in new DirectoryInfo(HistoryDirectory).EnumerateFiles("*.entry"))
            {
                DateTime previousWrite;
                if (historyWriteTimes.TryGetValue(file.FullName, out previousWrite) &&
                    previousWrite == file.LastWriteTimeUtc) continue;
                var app = ReadHistoryEntry(file.FullName);
                historyWriteTimes[file.FullName] = file.LastWriteTimeUtc;
                if (app == null) continue;
                HistoryApp prior;
                if (!history.TryGetValue(app.Identity, out prior))
                {
                    history[app.Identity] = app;
                    historyRevision++;
                }
                else if (app.LastSeen != prior.LastSeen || app.Icon != prior.Icon || app.ProcessKey != prior.ProcessKey)
                {
                    history[app.Identity] = app;
                    historyRevision++;
                }
            }
        }
        observedAppearanceRevision = revision;
        historyLoaded = true;
    }

    static HistoryApp ReadHistoryEntry(string file)
    {
        string[] fields;
        using (var stream = new FileStream(file, FileMode.Open, FileAccess.Read,
                                          FileShare.ReadWrite | FileShare.Delete))
        using (var reader = new StreamReader(stream, Utf8))
            fields = reader.ReadToEnd().Split('	');
        long timestamp;
        if (fields.Length != 5 || !Int64.TryParse(fields[4], NumberStyles.None, CultureInfo.InvariantCulture, out timestamp))
            return null;
        try
        {
            string identity = Decode(fields[1]);
            if (!IsStableKey(identity)) return null;
            return new HistoryApp {
                Title = Decode(fields[0]), Identity = identity, ProcessKey = Decode(fields[2]),
                Icon = fields[3], LastSeen = timestamp, File = file
            };
        }
        catch (FormatException) { return null; }
    }

    static string HistoryFile(string identity)
    {
        using (var sha = SHA256.Create())
        {
            byte[] hash = sha.ComputeHash(Utf8.GetBytes(identity.ToUpperInvariant()));
            return Path.Combine(HistoryDirectory, BitConverter.ToString(hash).Replace("-", "") + ".entry");
        }
    }

    static string[] HistoryFields(HistoryApp app)
    {
        return new[] { Encode(app.Title), Encode(app.Identity), Encode(app.ProcessKey),
            app.Icon, app.LastSeen.ToString(CultureInfo.InvariantCulture) };
    }

    static string AppDisplayName(string identity, string processKey)
    {
        if (IdentityComparer.Equals(identity, "id:Microsoft.Windows.Explorer")) return "文件资源管理器";
        if (processKey.StartsWith("path:", StringComparison.Ordinal))
        {
            string executable = processKey.Substring(5);
            try
            {
                var version = FileVersionInfo.GetVersionInfo(executable);
                if (!String.IsNullOrWhiteSpace(version.FileDescription)) return version.FileDescription.Trim();
                if (!String.IsNullOrWhiteSpace(version.ProductName)) return version.ProductName.Trim();
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            return Path.GetFileNameWithoutExtension(executable);
        }
        string name = identity.Substring(identity.IndexOf(':') + 1);
        return name.Substring(name.LastIndexOf('\\') + 1);
    }

    sealed class Presence
    {
        public bool Initialized;
        public readonly Dictionary<string, HashSet<string>> Visible =
            new Dictionary<string, HashSet<string>>(IdentityComparer);
        public readonly Dictionary<string, string> Suppressed = new Dictionary<string, string>(IdentityComparer);
    }

    static Presence ReadPresence()
    {
        var state = new Presence();
        if (!File.Exists(PresenceFile)) return state;
        foreach (string row in File.ReadAllLines(PresenceFile, Utf8))
        {
            if (row == "snapshot") { state.Initialized = true; continue; }
            var columns = row.Split('\t');
            if (columns.Length < 2) continue;
            string key;
            try { key = Decode(columns[1]); } catch (FormatException) { continue; }
            if (!IsStableKey(key)) continue;
            if (columns[0] == "removed")
            {
                string anchor = key;
                try
                {
                    if (columns.Length == 3) anchor = Decode(columns[2]);
                }
                catch (FormatException) { continue; }
                if (IsStableKey(anchor)) state.Suppressed[key] = anchor;
            }
            else if (columns[0] == "visible")
                state.Visible[key] = new HashSet<string>(columns.Skip(2), IdentityComparer);
        }
        return state;
    }

    static void WritePresence(Presence state)
    {
        var rows = new List<string>();
        if (state.Initialized) rows.Add("snapshot");
        foreach (var app in state.Visible.OrderBy(entry => entry.Key, IdentityComparer))
            rows.Add(String.Join("\t", new[] { "visible", Encode(app.Key) }.Concat(app.Value.OrderBy(instance => instance))));
        rows.AddRange(state.Suppressed.OrderBy(entry => entry.Key, IdentityComparer)
            .Select(entry => "removed\t" + Encode(entry.Key) + "\t" + Encode(entry.Value)));
        var value = String.Join("\n", rows);
        if (!File.Exists(PresenceFile) || File.ReadAllText(PresenceFile, Utf8) != value)
            WriteProfileText(PresenceFile, value);
    }

    sealed class CatalogApp
    {
        public string Key;
        public long Opened;
        public readonly HashSet<string> Keys = new HashSet<string>(IdentityComparer);
    }

    static string RuleKey(string entry)
    {
        foreach (var prefix in new[] { ShowPrefix, StackPrefix, SeparatePrefix })
            if (entry.StartsWith(prefix, StringComparison.Ordinal)) return entry.Substring(prefix.Length);
        return entry;
    }

    static Dictionary<string, CatalogApp> BuildCatalog(List<string> rules)
    {
        var aliases = new Dictionary<string, HashSet<string>>(IdentityComparer);
        Action<string, string> add = (identity, path) =>
        {
            if (!IsStableKey(identity) || !identity.StartsWith("id:", StringComparison.Ordinal) ||
                String.IsNullOrEmpty(path) || !path.StartsWith("path:", StringComparison.Ordinal)) return;
            HashSet<string> matches;
            if (!aliases.TryGetValue(path, out matches))
                aliases.Add(path, matches = new HashSet<string>(IdentityComparer));
            matches.Add(identity);
        };
        foreach (var app in history.Values) add(app.Identity, app.ProcessKey);
        foreach (var pair in ReadAliases()) add(pair.Key, pair.Value.ProcessKey);

        var known = new Dictionary<string, CatalogApp>(IdentityComparer);
        foreach (var key in history.Keys.Concat(rules.Select(RuleKey)).Where(IsStableKey))
        {
            HashSet<string> matches;
            string canonical = aliases.TryGetValue(key, out matches) && matches.Count == 1 ? matches.First() : key;
            CatalogApp entry;
            if (!known.TryGetValue(canonical, out entry))
                known.Add(canonical, entry = new CatalogApp { Key = canonical });
            entry.Keys.Add(canonical);
            entry.Keys.Add(key);
            HistoryApp app;
            if (history.TryGetValue(key, out app)) entry.Opened = Math.Max(entry.Opened, app.LastSeen);
        }
        foreach (var alias in aliases.Where(pair => pair.Value.Count == 1))
        {
            CatalogApp app;
            if (known.TryGetValue(alias.Value.First(), out app)) app.Keys.Add(alias.Key);
        }
        return known;
    }

    static bool IsCleanupRule(string entry, HashSet<string> keys)
    {
        if (keys.Contains(RuleKey(entry))) return true;
        var source = DecodeAutoStack(entry);
        return source != null && (keys.Contains(source.IconKey) || keys.Contains(source.StackKey));
    }

    static void FinishPendingCleanup(List<string> rules)
    {
        if (!File.Exists(PendingCleanupFile)) return;
        var keys = new HashSet<string>(
            File.ReadAllLines(PendingCleanupFile, Utf8).Select(Decode).Where(IsStableKey), IdentityComparer);
        rules.RemoveAll(entry => IsCleanupRule(entry, keys));
        SyncProfile(rules);
        foreach (var key in keys)
        {
            File.Delete(HistoryFile(key));
            history.Remove(key);
            historyWriteTimes.Remove(HistoryFile(key));
        }
        icons = icons.Where(pair => !keys.Any(key => pair.Key.Contains("|" + key + "|")))
                     .ToDictionary(pair => pair.Key, pair => pair.Value, IdentityComparer);
        lastVisibleItems = null;
        historyRevision++;
        MarkHistoryChanged();
        File.Delete(PendingCleanupFile);
    }

    static void PurgeApplications(IEnumerable<CatalogApp> targets, List<string> rules, Presence state)
    {
        var keys = new HashSet<string>(IdentityComparer);
        foreach (var app in targets)
        {
            keys.UnionWith(app.Keys);
            if (app.Keys.Any(state.Visible.ContainsKey))
                foreach (var key in app.Keys) state.Suppressed[key] = app.Key;
        }
        if (keys.Count == 0) return;
        WritePresence(state);
        WriteProfileText(PendingCleanupFile, String.Join("\n", keys.Select(Encode)));
        FinishPendingCleanup(rules);
    }

    static int TrimApplications(List<string> rules, Presence state, string keep = null)
    {
        var catalog = BuildCatalog(rules);
        int excess = Math.Max(0, catalog.Count - MaximumApps);
        if (excess > 0)
        {
            var oldest = catalog.Values.OrderBy(app => keep != null && app.Keys.Contains(keep))
                .ThenBy(app => app.Opened).ThenBy(app => app.Key, IdentityComparer).Take(excess).ToArray();
            PurgeApplications(oldest, rules, state);
        }
        return catalog.Count - excess;
    }

    static int RemoveApplications(string[] keys, out int count)
    {
        int remaining = 0;
        int removed = WithHistoryLock(() => WithRulesLock(() =>
        {
            LoadHistory();
            var rules = LoadRules();
            FinishPendingCleanup(rules);
            var catalog = BuildCatalog(rules);
            var selected = new HashSet<string>(keys, IdentityComparer);
            var targets = catalog.Values.Where(app => app.Keys.Overlaps(selected)).ToArray();
            PurgeApplications(targets, rules, ReadPresence());
            remaining = catalog.Count - targets.Length;
            return targets.Length;
        }));
        count = remaining;
        return removed;
    }

    static bool IsSuppressed(HistoryApp app, Presence state)
    {
        return state.Suppressed.ContainsKey(app.Identity) ||
            (app.Identity.StartsWith("path:", StringComparison.Ordinal) &&
             !String.IsNullOrEmpty(app.ProcessKey) && state.Suppressed.ContainsKey(app.ProcessKey));
    }

    static void RecordAppearance(HistoryApp seen, Presence state)
    {
        if (!IsStableKey(seen.Identity) || IsSuppressed(seen, state)) return;
        HashSet<string> windows;
        bool newlyVisible = !state.Visible.TryGetValue(seen.Identity, out windows);
        if (newlyVisible)
            state.Visible.Add(seen.Identity, windows = new HashSet<string>(IdentityComparer));
        bool previouslyObserved = windows.Count > 0;
        windows.RemoveWhere(value => !IsObservedWindowAlive(value) &&
            (seen.Windows == null || !seen.Windows.Contains(value)));
        bool newWindow = previouslyObserved && seen.Windows != null && seen.Windows.Any(value => !windows.Contains(value));
        if (seen.Windows != null) windows.UnionWith(seen.Windows);

        HistoryApp prior;
        bool newHistory = !history.TryGetValue(seen.Identity, out prior);
        if (!newHistory)
            seen.LastSeen = state.Initialized && (newlyVisible || newWindow)
                ? Math.Max(prior.LastSeen, seen.LastSeen) : prior.LastSeen;
        if (!newHistory && String.IsNullOrEmpty(seen.Icon)) seen.Icon = prior.Icon;
        if (!newHistory && String.IsNullOrEmpty(seen.ProcessKey)) seen.ProcessKey = prior.ProcessKey;
        if (!newHistory && seen.LastSeen == prior.LastSeen && seen.Icon == prior.Icon &&
            seen.ProcessKey == prior.ProcessKey) return;
        seen.Title = newHistory || seen.ProcessKey != prior.ProcessKey
            ? AppDisplayName(seen.Identity, seen.ProcessKey) : prior.Title;

        // A first appearance's pair is committed before its history receipt.
        // Retries see the existing rules and preserve both of them.
        if (newHistory) ApplyFirstAppearanceRule(seen);
        seen.File = HistoryFile(seen.Identity);
        WriteProfileText(seen.File, String.Join("\t", HistoryFields(seen)));
        history[seen.Identity] = seen;
        historyWriteTimes[seen.File] = File.GetLastWriteTimeUtc(seen.File);
        historyRevision++;
        MarkHistoryChanged();
        if (newHistory) WithRulesLock(() => TrimApplications(LoadRules(), state, seen.Identity));
    }

    static void SaveHistory(HistoryApp app)
    {
        WithHistoryLock(() =>
        {
            LoadHistory();
            WithRulesLock(() => { FinishPendingCleanup(LoadRules()); return true; });
            var state = ReadPresence();
            RecordAppearance(app, state);
            WritePresence(state);
            return true;
        });
    }

    static void ObserveHistory(Dictionary<string, HistoryApp> observed, HashSet<string> present, bool complete)
    {
        WithHistoryLock(() =>
        {
            LoadHistory();
            WithRulesLock(() => { FinishPendingCleanup(LoadRules()); return true; });
            var state = ReadPresence();
            foreach (var app in observed.Values) RecordAppearance(app, state);
            if (complete)
            {
                foreach (var key in state.Visible.Keys.Where(key => !present.Contains(key)).ToArray())
                    if (!state.Visible[key].Any(IsObservedWindowAlive)) state.Visible.Remove(key);
                // A pending deletion suppresses only the active window lifetime.
                var finished = state.Suppressed.GroupBy(entry => entry.Value, IdentityComparer)
                    .Where(group => !group.Any(entry => present.Contains(entry.Key) || state.Visible.ContainsKey(entry.Key)))
                    .SelectMany(group => group.Select(entry => entry.Key)).ToArray();
                foreach (var key in finished) state.Suppressed.Remove(key);
                state.Initialized = true;
            }
            WritePresence(state);
            return true;
        });
    }

    static void EmitHistory()
    {
        WithHistoryLock(() =>
        {
            LoadHistory();
            WithRulesLock(() => { FinishPendingCleanup(LoadRules()); return true; });
            foreach (var app in history.Values.OrderByDescending(value => value.LastSeen)
                         .ThenBy(value => value.Identity, IdentityComparer))
                Emit(new[] { "HISTORY" }.Concat(HistoryFields(app)).ToArray());
            return true;
        });
    }

    static void EmitStatus()
    {
        var primary = FindWindow("Shell_TrayWnd", null);
        int count = 0;
        var rules = WithHistoryLock(() => WithRulesLock(() =>
        {
            LoadHistory();
            var loaded = LoadRules();
            FinishPendingCleanup(loaded);
            count = TrimApplications(loaded, ReadPresence());
            return loaded;
        }));
        var applied = ReadProfile(AppliedFile);
        string state = GetProp(primary, LiveProperty) == IntPtr.Zero ? "inactive" :
            new HashSet<string>(rules, IdentityComparer).SetEquals(applied) ? "applied" : "pending";
        Emit("STATE", state);
        Emit("AUTO_NEW", GetAutoNew() ? "1" : "0");
        Emit("APP_COUNT", count.ToString(CultureInfo.InvariantCulture));
        foreach (string item in rules)
        {
            if (item.StartsWith(StackPrefix, StringComparison.Ordinal))
                Emit("STACK", Encode(item.Substring(StackPrefix.Length)), "1");
            else if (item.StartsWith(SeparatePrefix, StringComparison.Ordinal))
                Emit("STACK", Encode(item.Substring(SeparatePrefix.Length)), "0");
            else if (item.StartsWith(ShowPrefix, StringComparison.Ordinal))
                Emit("SHOW", Encode(item.Substring(ShowPrefix.Length)));
            else if (!item.StartsWith(AutoStackPrefix, StringComparison.Ordinal))
                Emit("RULE", Encode(item));
        }
    }

    static void Scan(string command)
    {
        bool includeItems = command != "scan-hidden";
        var itemRows = includeItems ? new List<VisibleItem>() : null;
        long sampledAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long tick = Stopwatch.GetTimestamp();
        var observed = new Dictionary<string, HistoryApp>(IdentityComparer);
        var present = new HashSet<string>(IdentityComparer);
        bool complete = true;
        var aliases = ReadAliases();
        var taskbars = new List<IntPtr>();
        EnumWindows((hwnd, lParam) =>
        {
            var windowClass = new StringBuilder(64);
            GetClassName(hwnd, windowClass, windowClass.Capacity);
            if (windowClass.ToString() == "Shell_TrayWnd" || windowClass.ToString() == "Shell_SecondaryTrayWnd")
                taskbars.Add(hwnd);
            return true;
        }, IntPtr.Zero);
        if (taskbars.Count == 0)
            throw new InvalidOperationException("找不到 Windows 任务栏");

        var nextIcons = new Dictionary<string, IconSample>(IdentityComparer);
        var paths = new Dictionary<uint, string>();
        var condition = new PropertyCondition(AutomationElement.ClassNameProperty, ButtonClass);
        var cache = new CacheRequest { AutomationElementMode = AutomationElementMode.None };
        cache.Add(AutomationElement.AutomationIdProperty);
        cache.Add(AutomationElement.IsOffscreenProperty);
        if (includeItems)
        {
            cache.Add(AutomationElement.NameProperty);
            cache.Add(AutomationElement.BoundingRectangleProperty);
        }
        foreach (IntPtr taskbar in taskbars)
        {
            AutomationElement root = AutomationElement.FromHandle(taskbar);
            AutomationElementCollection taskButtons;
            using (cache.Activate())
                taskButtons = root.FindAll(TreeScope.Descendants, condition);
            foreach (AutomationElement element in taskButtons)
            {
                try
                {
                    var data = element.Cached;
                    string id = data.AutomationId ?? "";
                    string title = includeItems ? data.Name : "";
                    var button = ResolveButton(id, aliases, paths);
                    if (IsStableKey(button.Identity) && !button.KnownPinnedOnly) present.Add(button.Identity);
                    if (data.IsOffscreen) continue;
                    var window = button.Window;
                    var owner = button.Owner;
                    var path = button.Path;
                    var appId = button.AppId;
                    var key = button.Identity;
                    var processKey = button.ProcessKey;
                    var windowCount = button.WindowCount;
                    if (includeItems && owner != 0)
                    {
                        var windowTitle = new StringBuilder(1024);
                        GetWindowText(window, windowTitle, windowTitle.Capacity);
                        if (windowTitle.Length > 0) title = windowTitle.ToString();
                    }
                    string iconCacheId = id + "|" + key + "|" + owner;
                    IconSample icon;
                    if (!icons.TryGetValue(iconCacheId, out icon) ||
                        (includeItems && tick - icon.SampledAt >= IconRefreshInterval))
                    {
                        if (String.IsNullOrEmpty(path) && processKey.StartsWith("path:", StringComparison.Ordinal))
                            path = processKey.Substring(5);
                        icon = new IconSample { Image = ReadIcon(GetWindowIcon(window), path, appId), SampledAt = tick };
                    }
                    nextIcons[iconCacheId] = icon;
                    if (IsStableKey(key) && !button.KnownPinnedOnly)
                    {
                        HistoryApp appearance;
                        if (!observed.TryGetValue(key, out appearance))
                        {
                            appearance = new HistoryApp {
                                Identity = key, ProcessKey = processKey, Icon = icon.Image,
                                LastSeen = sampledAt, Windows = new HashSet<string>(IdentityComparer),
                            };
                            observed[key] = appearance;
                        }
                        if (String.IsNullOrEmpty(appearance.Icon)) appearance.Icon = icon.Image;
                        appearance.Windows.UnionWith(ObservedWindows(button, aliases));
                    }
                    if (includeItems)
                    {
                        string liveIcon = "", liveStack = "";
                        string layoutKey = "layout:" + key;
                        if (owner != 0)
                            layoutKey = "layout:window:" + window.ToInt64().ToString(CultureInfo.InvariantCulture);
                        Alias layout;
                        if (aliases.TryGetValue(layoutKey, out layout) &&
                            (owner == 0 || layout.ProcessKey == owner.ToString(CultureInfo.InvariantCulture)))
                        {
                            int flags = layout.WindowCount;
                            if ((flags & 1) != 0) liveIcon = (flags & 2) != 0 ? "1" : "0";
                            if ((flags & 8) != 0) liveStack = (flags & 4) != 0 ? "1" : "0";
                        }
                        Alias group;
                        if (owner != 0 && aliases.TryGetValue(key, out group) && group.WindowCount > 1)
                            liveStack = "0";
                        itemRows.Add(new VisibleItem {
                            Title = title, Identity = key, ProcessKey = processKey, Icon = icon.Image,
                            Width = Math.Round(data.BoundingRectangle.Width), Windows = windowCount, Instance = id,
                            LiveIcon = liveIcon, LiveStack = liveStack
                        });
                    }
                }
                catch (ElementNotAvailableException) { complete = false; }
            }
        }
        icons = nextIcons;
        try { ObserveHistory(observed, present, complete); }
        catch (IOException ex) { Emit("HERROR", Encode(ex.Message)); }
        catch (UnauthorizedAccessException ex) { Emit("HERROR", Encode(ex.Message)); }
        EmitStatus();
        Emit("HREV", historyRevision.ToString(CultureInfo.InvariantCulture));
        if (includeItems)
        {
            if (command == "scan-view" && lastVisibleItems != null && lastVisibleItems.SequenceEqual(itemRows))
                Emit("SAME");
            else
                foreach (var row in itemRows) row.Write();
            lastVisibleItems = itemRows;
        }
    }

    sealed class ButtonIdentity
    {
        public IntPtr Window;
        public uint Owner;
        public string Path, AppId, Identity, ProcessKey;
        public int WindowCount;
        public bool KnownPinnedOnly;
    }

    static ButtonIdentity ResolveButton(string id, Dictionary<string, Alias> aliases, Dictionary<uint, string> paths)
    {
        var result = new ButtonIdentity();
        if (id.StartsWith(WindowPrefix, StringComparison.OrdinalIgnoreCase))
        {
            long handle;
            if (Int64.TryParse(id.Substring(WindowPrefix.Length), NumberStyles.HexNumber,
                              CultureInfo.InvariantCulture, out handle))
                result.Window = new IntPtr(handle);
            if (IsWindow(result.Window))
            {
                GetWindowThreadProcessId(result.Window, out result.Owner);
                Alias group;
                string windowKey = "window:" + result.Window.ToInt64().ToString(CultureInfo.InvariantCulture);
                if (result.Owner != 0 && aliases.TryGetValue(windowKey, out group) &&
                    (uint)group.WindowCount == result.Owner && group.ProcessKey.StartsWith("id:", StringComparison.Ordinal))
                    result.AppId = group.ProcessKey.Substring(3);
                if (String.IsNullOrEmpty(result.AppId))
                    ResolveWindow(result.Window, out result.Path, out result.AppId);
                else if (!paths.TryGetValue(result.Owner, out result.Path))
                {
                    result.Path = ProcessPath(result.Owner);
                    paths[result.Owner] = result.Path;
                }
            }
        }
        else if (id.StartsWith(AppPrefix, StringComparison.OrdinalIgnoreCase))
        {
            result.AppId = id.Substring(AppPrefix.Length);
        }
        if (!String.IsNullOrEmpty(result.AppId) && result.AppId.StartsWith("*PID", StringComparison.OrdinalIgnoreCase))
        {
            Alias temporary;
            if (String.IsNullOrEmpty(result.Path) && aliases.TryGetValue("id:" + result.AppId, out temporary) &&
                temporary.ProcessKey.StartsWith("path:", StringComparison.Ordinal))
                result.Path = temporary.ProcessKey.Substring(5);
            result.AppId = null;
        }
        result.Identity = !String.IsNullOrEmpty(result.AppId) ? "id:" + result.AppId :
            !String.IsNullOrEmpty(result.Path) ? "path:" + result.Path : "";
        result.ProcessKey = !String.IsNullOrEmpty(result.Path) ? "path:" + result.Path : "";
        result.WindowCount = result.Owner != 0 ? 1 : 0;
        Alias alias;
        if (result.WindowCount == 0 && aliases.TryGetValue(result.Identity, out alias))
        {
            if (result.ProcessKey.Length == 0) result.ProcessKey = alias.ProcessKey;
            result.WindowCount = alias.WindowCount;
            result.KnownPinnedOnly = alias.WindowCount == 0;
        }
        return result;
    }

    static bool IsObservedWindowAlive(string key)
    {
        var fields = key.Split(':');
        long hwnd;
        uint owner;
        uint known;
        if (fields.Length != 2 || !Int64.TryParse(fields[0], out hwnd) || !UInt32.TryParse(fields[1], out known))
            return false;
        return GetWindowThreadProcessId(new IntPtr(hwnd), out owner) != 0 && owner == known;
    }

    static IEnumerable<string> ObservedWindows(ButtonIdentity app, Dictionary<string, Alias> aliases)
    {
        if (app.Owner != 0)
        {
            yield return app.Window.ToInt64().ToString(CultureInfo.InvariantCulture) + ":" + app.Owner;
            yield break;
        }
        foreach (var pair in aliases)
        {
            if (!pair.Key.StartsWith("window:", StringComparison.Ordinal) ||
                !IdentityComparer.Equals(app.Identity, pair.Value.ProcessKey)) continue;
            string key = pair.Key.Substring(7) + ":" + pair.Value.WindowCount;
            if (IsObservedWindowAlive(key)) yield return key;
        }
    }

    static void RecordVisibleButton(AutomationElement element)
    {
        if (element == null) return;
        try
        {
            // ChildAdded identifies the actual new button when the provider
            // emits an individual event. Read cached evidence before the next
            // snapshot so an already removed button can still be retained.
            var observed = element.Cached;
            if (observed.ClassName != ButtonClass || observed.IsOffscreen) return;
            var aliases = ReadAliases();
            var button = ResolveButton(observed.AutomationId ?? "", aliases, new Dictionary<uint, string>());
            if (!IsStableKey(button.Identity) || button.KnownPinnedOnly) return;
            string path = button.Path;
            if (String.IsNullOrEmpty(path) && button.ProcessKey.StartsWith("path:", StringComparison.Ordinal))
                path = button.ProcessKey.Substring(5);
            SaveHistory(new HistoryApp {
                Identity = button.Identity, ProcessKey = button.ProcessKey,
                Title = AppDisplayName(button.Identity, button.ProcessKey),
                File = HistoryFile(button.Identity),
                Icon = ReadIcon(GetWindowIcon(button.Window), path, button.AppId),
                LastSeen = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Windows = new HashSet<string>(ObservedWindows(button, aliases), IdentityComparer)
            });
        }
        catch (ElementNotAvailableException) { }
        catch (InvalidOperationException) { }
        catch (UnauthorizedAccessException) { }
        catch (IOException) { }
    }

    static string ProcessPath(uint pid)
    {
        var process = OpenProcess(0x1000, false, pid);
        if (process == IntPtr.Zero) return null;
        try
        {
            uint size = 32768;
            var name = new StringBuilder((int)size);
            return QueryFullProcessImageName(process, 0, name, ref size) ? name.ToString() : null;
        }
        finally { CloseHandle(process); }
    }

    static void ResolveWindow(IntPtr window, out string path, out string appId)
    {
        path = null;
        appId = null;
        IPropertyStore store;
        var iid = typeof(IPropertyStore).GUID;
        if (SHGetPropertyStoreForWindow(window, ref iid, out store) == 0 && store != null)
        {
            var prop = new PropertyKey { Format = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), Id = 5 };
            PropVariant value;
            try
            {
                if (store.GetValue(ref prop, out value) >= 0)
                {
                    try { if (value.Type == 31) appId = Marshal.PtrToStringUni(value.Pointer); }
                    finally { PropVariantClear(ref value); }
                }
            }
            finally { Marshal.ReleaseComObject(store); }
        }
        uint pid;
        GetWindowThreadProcessId(window, out pid);
        var process = OpenProcess(0x1000, false, pid);
        if (process == IntPtr.Zero) return;
        try
        {
            uint size = 32768;
            var name = new StringBuilder((int)size);
            if (QueryFullProcessImageName(process, 0, name, ref size))
                path = name.ToString();
            if (String.IsNullOrEmpty(appId))
            {
                uint length = 0;
                if (GetApplicationUserModelId(process, ref length, null) == 122 && length < 512)
                {
                    var buffer = new StringBuilder((int)length);
                    if (GetApplicationUserModelId(process, ref length, buffer) == 0)
                        appId = buffer.ToString();
                }
            }
        }
        finally { CloseHandle(process); }
    }

    static IntPtr GetWindowIcon(IntPtr window)
    {
        if (window == IntPtr.Zero) return IntPtr.Zero;
        IntPtr icon;
        SendMessageTimeout(window, 0x007F, new IntPtr(2), IntPtr.Zero, 2, 35, out icon);
        if (icon == IntPtr.Zero) icon = GetClassLongPtr(window, -34);
        if (icon == IntPtr.Zero) icon = GetClassLongPtr(window, -14);
        return icon;
    }

    static string ReadIcon(IntPtr icon, string path, string appId)
    {
        bool owned = false;
        if (icon == IntPtr.Zero && !String.IsNullOrEmpty(appId))
        {
            IntPtr item;
            if (SHParseDisplayName(@"shell:AppsFolder\" + appId, IntPtr.Zero, out item, 0, IntPtr.Zero) == 0)
            {
                ShellFileInfo info;
                try
                {
                    SHGetFileInfoPidl(item, 0, out info, Marshal.SizeOf(typeof(ShellFileInfo)), 0x108);
                    icon = info.Icon;
                    owned = true;
                }
                finally { Marshal.FreeCoTaskMem(item); }
            }
        }
        if (icon == IntPtr.Zero && !String.IsNullOrEmpty(path))
        {
            ShellFileInfo info;
            SHGetFileInfo(path, 0, out info, Marshal.SizeOf(typeof(ShellFileInfo)), 0x100);
            icon = info.Icon;
            owned = true;
        }
        if (icon == IntPtr.Zero) return "";
        try
        {
            using (var bitmap = Icon.FromHandle(icon).ToBitmap())
            using (var stream = new MemoryStream())
            {
                bitmap.Save(stream, ImageFormat.Png);
                return Convert.ToBase64String(stream.ToArray());
            }
        }
        catch (ArgumentException) { return ""; }
        finally { if (owned) DestroyIcon(icon); }
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey { public Guid Format; public uint Id; }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    struct PropVariant
    {
        [FieldOffset(0)] public ushort Type;
        [FieldOffset(8)] public IntPtr Pointer;
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ShellFileInfo
    {
        public IntPtr Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }

    delegate bool EnumProc(IntPtr hwnd, IntPtr data);

    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string className, string title);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetProp(IntPtr window, string name);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback, IntPtr data);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr window, StringBuilder result, int length);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr window, StringBuilder result, int length);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr SendMessageTimeout(IntPtr window, uint message, IntPtr wParam, IntPtr lParam, uint flags, uint timeout, out IntPtr result);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetClassLongPtr(IntPtr window, int index);
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr icon);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder value, ref uint capacity);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern int GetApplicationUserModelId(IntPtr process, ref uint length, StringBuilder value);
    [DllImport("shell32.dll")] static extern int SHGetPropertyStoreForWindow(IntPtr window, ref Guid iid, out IPropertyStore store);
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant value);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] static extern int SHParseDisplayName(string name, IntPtr context, out IntPtr item, uint inputAttributes, IntPtr outputAttributes);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] static extern IntPtr SHGetFileInfo(string path, uint attributes, out ShellFileInfo result, int size, uint flags);
    [DllImport("shell32.dll", EntryPoint = "SHGetFileInfoW")] static extern IntPtr SHGetFileInfoPidl(IntPtr item, uint attributes, out ShellFileInfo result, int size, uint flags);
}

internal static class WindowsTrayMenu
{
    const uint PopupStyle = 0x80000000;
    const uint ToolWindowStyle = 0x80;
    const uint DisabledItem = 0x03;
    const uint PopupFlags = 0x02 | 0x08 | 0x20 | 0x80 | 0x100 | 0x10000;

    public static uint Show(string status)
    {
        Point point;
        if (!GetCursorPos(out point)) throw new Win32Exception();
        IntPtr window = CreateWindowEx(ToolWindowStyle, "STATIC", null, PopupStyle,
                                        point.X, point.Y, 0, 0,
                                        IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        if (window == IntPtr.Zero) throw new Win32Exception();
        IntPtr menu = IntPtr.Zero;
        try
        {
            menu = CreatePopupMenu();
            if (menu == IntPtr.Zero ||
                !AppendMenu(menu, 0, new UIntPtr(1), "打开配置器") ||
                !AppendMenu(menu, DisabledItem, new UIntPtr(2), status) ||
                !AppendMenu(menu, 0, new UIntPtr(3), "退出"))
                throw new Win32Exception();

            // A background tray menu still needs its owner in the foreground
            // so clicks outside dismiss it. Its owner is a zero-sized tool window.
            SetForegroundWindow(window);
            uint action = TrackPopupMenuEx(menu, PopupFlags, point.X, point.Y, window, IntPtr.Zero);
            PostMessage(window, 0, IntPtr.Zero, IntPtr.Zero);
            return action;
        }
        finally
        {
            if (menu != IntPtr.Zero) DestroyMenu(menu);
            DestroyWindow(window);
        }
    }

    [StructLayout(LayoutKind.Sequential)] struct Point { public int X, Y; }
    [DllImport("user32.dll")] static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateWindowEx(uint extendedStyle, string className, string title, uint style,
                                       int x, int y, int width, int height, IntPtr parent, IntPtr menu,
                                       IntPtr module, IntPtr parameters);
    [DllImport("user32.dll", SetLastError = true)] static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool AppendMenu(IntPtr menu, uint flags, UIntPtr command, string label);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]
    static extern uint TrackPopupMenuEx(IntPtr menu, uint flags, int x, int y, IntPtr owner, IntPtr parameters);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern bool PostMessage(IntPtr window, uint message, IntPtr wparam, IntPtr lparam);
    [DllImport("user32.dll")] static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll")] static extern bool DestroyWindow(IntPtr window);
}
