using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

// Use native UI Automation for the resident event bridge. The .NET Framework
// client registers managed HWND proxies that accumulate with desktop WinEvents.
internal sealed class TaskbarAutomationEvents : IDisposable
{
    const int Subtree = 7;
    const int CachedOnly = 0;
    const int ChildAdded = 0;
    const int NameProperty = 30005;
    const int AutomationIdProperty = 30011;
    const int ClassNameProperty = 30012;
    const int IsOffscreenProperty = 30022;
    static readonly int[] PropertyChanges = { NameProperty, IsOffscreenProperty };

    readonly Dictionary<IntPtr, IAutomationElement> roots = new Dictionary<IntPtr, IAutomationElement>();
    readonly Callback handler;
    IAutomationClient client;
    IAutomationEventGroup group;

    internal TaskbarAutomationEvents(string buttonClass, Action<string> recordButton, Action signal)
    {
        handler = new Callback(buttonClass, recordButton, signal);
        try
        {
            var id = new Guid("e22ad333-b25f-460c-83d0-0581107395c9"); // CUIAutomation8
            client = (IAutomationClient)Activator.CreateInstance(Type.GetTypeFromCLSID(id, true));
            IAutomationCacheRequest cache = null;
            try
            {
                client.CreateCacheRequest(out cache);
                cache.SetElementMode(CachedOnly);
                cache.AddProperty(ClassNameProperty);
                cache.AddProperty(AutomationIdProperty);
                cache.AddProperty(IsOffscreenProperty);
                client.CreateEventHandlerGroup(out group);
                group.AddStructureChangedHandler(Subtree, cache, handler);
                group.AddPropertyChangedHandler(Subtree, cache, handler, PropertyChanges, PropertyChanges.Length);
            }
            finally { Release(cache); }
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    internal bool Contains(IntPtr window) { return roots.ContainsKey(window); }

    // Subscription changes and releases stay on the event loop's MTA.
    // If a disappearing root cannot be unbound, let the event bridge close
    // all registrations and reconnect from a fresh enumeration.
    internal void Rebind(HashSet<IntPtr> windows, HashSet<IntPtr> destroyed)
    {
        foreach (IntPtr window in roots.Keys.Where(window => destroyed.Contains(window) || !windows.Contains(window)).ToArray())
        {
            var root = roots[window];
            client.RemoveEventHandlerGroup(root, group);
            roots.Remove(window);
            Release(root);
        }

        foreach (IntPtr window in windows.Where(window => !roots.ContainsKey(window)))
        {
            IAutomationElement root = null;
            try
            {
                client.ElementFromHandle(window, out root);
                client.AddEventHandlerGroup(root, group);
                roots.Add(window, root);
                root = null;
            }
            finally { Release(root); }
        }
    }

    public void Dispose()
    {
        handler.Stop();
        try
        {
            if (client != null) client.RemoveAllEventHandlers();
        }
        finally
        {
            foreach (var root in roots.Values) Release(root);
            roots.Clear();
            Release(group);
            Release(client);
            group = null;
            client = null;
        }
    }

    static void Release(object value)
    {
        if (value != null) Marshal.ReleaseComObject(value);
    }

    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    sealed class Callback : ITaskbarStructureChanged, ITaskbarPropertyChanged
    {
        readonly string buttonClass;
        readonly Action<string> recordButton;
        readonly Action signal;
        int stopped;

        internal Callback(string buttonClass, Action<string> recordButton, Action signal)
        {
            this.buttonClass = buttonClass;
            this.recordButton = recordButton;
            this.signal = signal;
        }

        internal void Stop() { Volatile.Write(ref stopped, 1); }

        static object Cached(IAutomationElement sender, int property)
        {
            object value;
            return sender.GetCachedPropertyValue(property, out value) >= 0 ? value : null;
        }

        void RecordVisibleButton(IAutomationElement sender)
        {
            if (!Object.Equals(Cached(sender, IsOffscreenProperty), false)) return;
            var id = Cached(sender, AutomationIdProperty) as string;
            if (!String.IsNullOrEmpty(id)) recordButton(id);
        }

        public void HandleStructureChanged(IAutomationElement sender, int change, IntPtr runtimeId)
        {
            if (Volatile.Read(ref stopped) != 0) return;
            if (sender != null && change == ChildAdded && Object.Equals(Cached(sender, ClassNameProperty), buttonClass))
                RecordVisibleButton(sender);
            signal();
        }

        public void HandlePropertyChanged(IAutomationElement sender, int property, object newValue)
        {
            if (Volatile.Read(ref stopped) != 0 || sender == null ||
                !Object.Equals(Cached(sender, ClassNameProperty), buttonClass)) return;
            if (property == IsOffscreenProperty && Object.Equals(newValue, false))
                RecordVisibleButton(sender);
            signal();
        }
    }

    // Method slots follow the Windows SDK's UIAutomationClient.h.
    [ComImport, Guid("aae072da-29e3-413d-87a7-192dbf81ed10"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAutomationClient
    {
        void _VtblGap1_3();
        void ElementFromHandle(IntPtr window, out IAutomationElement root);
        void _VtblGap2_13();
        void CreateCacheRequest(out IAutomationCacheRequest cache);
        void _VtblGap3_20();
        void RemoveAllEventHandlers();
        void _VtblGap4_28();
        void CreateEventHandlerGroup(out IAutomationEventGroup group);
        void AddEventHandlerGroup(IAutomationElement element, IAutomationEventGroup group);
        void RemoveEventHandlerGroup(IAutomationElement element, IAutomationEventGroup group);
    }

    [ComImport, Guid("b32a92b5-bc25-4078-9c08-d7ee95c48e03"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAutomationCacheRequest
    {
        void AddProperty(int property);
        void _VtblGap1_7();
        void SetElementMode(int mode);
    }

    [ComImport, Guid("c9ee12f2-c13b-4408-997c-639914377f4e"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAutomationEventGroup
    {
        void _VtblGap1_4();
        void AddPropertyChangedHandler(int scope, IAutomationCacheRequest cache, ITaskbarPropertyChanged handler,
                                       [MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 4)] int[] properties, int count);
        void AddStructureChangedHandler(int scope, IAutomationCacheRequest cache, ITaskbarStructureChanged handler);
    }
}

[ComImport, Guid("d22108aa-8ac5-49a5-837b-37bbb3d7591e"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAutomationElement
{
    void _VtblGap1_9();
    [PreserveSig]
    int GetCachedPropertyValue(int property, [MarshalAs(UnmanagedType.Struct)] out object value);
}

[ComImport, Guid("e81d1b4e-11c5-42f8-9754-e7036c79f054"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface ITaskbarStructureChanged
{
    void HandleStructureChanged(IAutomationElement sender, int change, IntPtr runtimeId);
}

[ComImport, Guid("40cd37d4-c756-4b0c-8c6f-bddfeeb13b50"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface ITaskbarPropertyChanged
{
    void HandlePropertyChanged(IAutomationElement sender, int property,
                               [MarshalAs(UnmanagedType.Struct)] object newValue);
}
