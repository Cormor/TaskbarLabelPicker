// ==WindhawkMod==
// @id              taskbar-label-picker
// @name            任务栏标签白名单
// @description     仅隐藏所选 Windows 11 任务栏应用的文字标签
// @version         0.1.6
// @author          砺行
// @include         explorer.exe
// @architecture    x86-64
// @compilerOptions -lole32 -loleaut32 -lruntimeobject -ladvapi32 -lshell32
// ==/WindhawkMod==

// SPDX-License-Identifier: GPL-3.0-only
// Native HasLabel interception adapted from m417z's Taskbar Labels for Windows 11
// https://github.com/ramensoftware/windhawk-mods/blob/5abbcc2ff02fe80a9b46afedbf3f6474f1169f65/mods/taskbar-labels.wh.cpp
// Taskbar host traversal and item bindings adapted from m417z's taskbar-start-button-position
// and taskbar-button-scroll, both distributed under GPL-3.0.
// https://github.com/ramensoftware/windhawk-mods/tree/main/mods

// ==WindhawkModReadme==
/*
Use the accompanying 任务栏标签 picker to manage icon-only applications.
This mod uses the native Windows 11 taskbar and follows Windows' taskbar settings.
*/
// ==/WindhawkModReadme==

#include <windhawk_utils.h>
#include <inspectable.h>
#include <shlobj.h>
#include <winrt/Windows.Foundation.Collections.h>
#undef GetCurrentTime
#include <winrt/Windows.UI.Xaml.Media.h>
#include <winrt/Windows.UI.Xaml.Controls.h>
#include <winrt/Windows.UI.Xaml.Data.h>
#define WH_WINRT_WINUI2
#include <winrt/Microsoft.UI.Xaml.Controls.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <map>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr wchar_t kTrayProperty[] = L"Lixing.TaskbarLabelPicker.Active.1";
using Rules = std::vector<std::wstring>;
std::shared_ptr<const Rules> g_rules = std::make_shared<Rules>();
std::shared_ptr<const Rules> g_precedingRules = std::make_shared<Rules>();
std::atomic<bool> g_hasStackRules;
std::mutex g_aliasLock;
struct GroupAlias {
    std::wstring processKey;
    uint32_t windows;
    bool operator==(const GroupAlias&) const = default;
};
using GroupAliases = std::map<std::wstring, GroupAlias>;
GroupAliases g_aliases;
std::atomic<bool> g_unloading;
HANDLE g_stop;
HANDLE g_aliasChanged;
HANDLE g_worker;
std::mutex g_startLock;
bool g_afterInit;
bool g_taskbarReady;
std::atomic<bool> g_taskbarAttempted;

// WindowsUdk.UI.Shell interfaces, declared up to the last used method. A WinRT
// interface doesn't change once published, so a successful query by IID
// guarantees the layout.

// {b081d9d6-9b45-5363-8a4d-854add6abe7e}
constexpr winrt::guid IID_ITaskItem{
    0xb081d9d6,
    0x9b45,
    0x5363,
    {0x8a, 0x4d, 0x85, 0x4a, 0xdd, 0x6a, 0xbe, 0x7e}};

struct ITaskItem : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_AppId(void** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_UniqueId() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_WindowId(HWND* value) = 0;
};

// {a152e779-93df-5be2-af2c-d342452b0ce0}
constexpr winrt::guid IID_ITaskGroup{
    0xa152e779,
    0x93df,
    0x5be2,
    {0xaf, 0x2c, 0xd3, 0x42, 0x45, 0x2b, 0x0c, 0xe0}};

struct ITaskGroup : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_AppId(void** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_UniqueId() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DisplayName() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AccessibleName() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Icon() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_OverlayIcon() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_OverlayIconDescription() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Badge() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsRequestingAttention() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Progress() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_VisualState() = 0;
    virtual HRESULT STDMETHODCALLTYPE put_VisualState() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Bounds() = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Bounds() = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Items(void** value) = 0;
};

HWND GetTaskItemWindow(IUnknown* taskItemUnknown) {
    winrt::com_ptr<ITaskItem> taskItem;
    HRESULT hr =
        taskItemUnknown->QueryInterface(IID_ITaskItem, taskItem.put_void());
    if (FAILED(hr)) {
        return nullptr;
    }

    HWND hWnd = nullptr;
    if (FAILED(taskItem->get_WindowId(&hWnd))) {
        return nullptr;
    }

    return hWnd;
}

bool ContainsRule(const Rules& rules, const std::wstring& key) {
    return std::any_of(rules.begin(), rules.end(), [&key](const std::wstring& rule) {
        return CompareStringOrdinal(rule.c_str(), -1, key.c_str(), -1, TRUE) == CSTR_EQUAL;
    });
}

std::wstring WindowProcessPath(HWND window) {
    DWORD pid = 0;
    if (!window || !GetWindowThreadProcessId(window, &pid)) return {};

    auto process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) return {};
    WCHAR path[32768];
    DWORD length = ARRAYSIZE(path);
    bool resolved = QueryFullProcessImageName(process, 0, path, &length);
    CloseHandle(process);
    return resolved ? std::wstring(path, length) : std::wstring{};
}

enum class Label { System, Hide, Show };

Label LabelForKey(const Rules& rules, const std::wstring& key) {
    if (key.empty()) return Label::System;
    if (ContainsRule(rules, key)) return Label::Hide;
    if (ContainsRule(rules, L"show:" + key)) return Label::Show;
    return Label::System;
}

Label LabelForWindow(std::wstring_view appId, HWND window) {
    auto rules = std::atomic_load(&g_rules);
    if (!appId.empty()) {
        auto label = LabelForKey(*rules, L"id:" + std::wstring(appId));
        if (label != Label::System) return label;
    }
    auto path = WindowProcessPath(window);
    return path.empty() ? Label::System : LabelForKey(*rules, L"path:" + path);
}

enum class Grouping { System, Stack, Separate };

struct GroupIdentity {
    std::wstring appKey;
    std::wstring processKey;
    uint32_t windows = 0;
};

Grouping GroupingFor(const Rules& rules, const GroupIdentity& group) {
    for (const auto& key : {&group.appKey, &group.processKey}) {
        if (key->empty()) continue;
        if (ContainsRule(rules, L"separate:" + *key)) return Grouping::Separate;
        if (ContainsRule(rules, L"stack:" + *key)) return Grouping::Stack;
    }
    return Grouping::System;
}

bool HasStackRules(const Rules& rules) {
    return std::any_of(rules.begin(), rules.end(), [](const std::wstring& rule) {
        return rule.starts_with(L"stack:") || rule.starts_with(L"separate:");
    });
}

void* ITaskListWindowViewModel_vftable;
using GetModelItem = HRESULT(WINAPI*)(void*, void**);
GetModelItem ITaskListWindowViewModel_get_TaskItem;
GetModelItem TaskListGroupViewModel_ITaskbarAppItemViewModel_get_TaskGroup;
void* GroupViewModelDefaultVtable;
void* TaskListGroupViewModel_ITaskbarAppItemViewModel_vftable;
void* GroupCollapsibleVtable;
void* CollectionVectorVtable;
constexpr winrt::guid kGroupModelInterface{
    0xb69e8fb1, 0xa966, 0x55f4, {0x86, 0x5d, 0x7b, 0xb7, 0x4a, 0x8f, 0x38, 0x08}};
constexpr winrt::guid kAppItemModelInterface{
    0xad068c6c, 0xec28, 0x51c1, {0x9f, 0x63, 0x53, 0x8e, 0xd6, 0x9b, 0x22, 0x8b}};

GroupIdentity GetGroupIdentity(void* appItemAbi) {
    GroupIdentity result;
    if (!appItemAbi) return result;
    winrt::com_ptr<IUnknown> unknown;
    if (FAILED(TaskListGroupViewModel_ITaskbarAppItemViewModel_get_TaskGroup(
            appItemAbi, unknown.put_void())) || !unknown) return result;

    winrt::com_ptr<ITaskGroup> group;
    if (FAILED(unknown->QueryInterface(IID_ITaskGroup, group.put_void()))) return result;

    winrt::hstring appId;
    if (SUCCEEDED(group->get_AppId(winrt::put_abi(appId))) && !appId.empty()) {
        result.appKey = L"id:" + std::wstring(appId);
    }
    std::vector<std::pair<HWND, DWORD>> windowAliases;
    using winrt::Windows::Foundation::IInspectable;
    winrt::Windows::Foundation::Collections::IVectorView<IInspectable> items;
    if (SUCCEEDED(group->get_Items(winrt::put_abi(items))) && items) {
        try {
            result.windows = items.Size();
            for (uint32_t index = 0; index < result.windows; ++index) {
                auto item = items.GetAt(index);
                auto window = GetTaskItemWindow(static_cast<IUnknown*>(winrt::get_abi(item)));
                if (result.processKey.empty()) {
                    auto path = WindowProcessPath(window);
                    if (!path.empty()) result.processKey = L"path:" + path;
                }
                DWORD pid = 0;
                if (window && GetWindowThreadProcessId(window, &pid)) {
                    windowAliases.emplace_back(window, pid);
                }
            }
        } catch (const winrt::hresult_error&) {
            // The task group can change while the underlying shell is enumerated.
        }
    }
    if (!result.appKey.empty()) {
        std::lock_guard lock(g_aliasLock);
        bool changed = false;
        auto remember = [&changed](const std::wstring& key, GroupAlias entry) {
            auto current = g_aliases.find(key);
            if (current != g_aliases.end()) {
                if (current->second == entry) return;
                current->second = std::move(entry);
                changed = true;
            } else if (g_aliases.size() < 1024) {
                g_aliases.emplace(key, std::move(entry));
                changed = true;
            }
        };
        remember(result.appKey, GroupAlias{result.processKey, result.windows});
        // Match the Shell's actual group for each window, even when the window
        // itself does not publish an AppUserModelID through the property store.
        for (const auto& [window, pid] : windowAliases) {
            auto key = L"window:" + std::to_wstring(reinterpret_cast<uintptr_t>(window));
            remember(key, GroupAlias{result.appKey, pid});
        }
        if (changed) SetEvent(g_aliasChanged);
    }
    return result;
}

GroupIdentity IdentifyGroupModel(void* implementation) {
    auto abi = static_cast<void**>(implementation);
    if (!abi || abi[1] != GroupViewModelDefaultVtable) return {};
    auto group = reinterpret_cast<IUnknown*>(abi + 1);
    winrt::com_ptr<IUnknown> item;
    if (FAILED(group->QueryInterface(kAppItemModelInterface, item.put_void())) || !item ||
        *reinterpret_cast<void**>(item.get()) != TaskListGroupViewModel_ITaskbarAppItemViewModel_vftable) {
        return {};
    }
    return GetGroupIdentity(item.get());
}

using GroupOperation = void(WINAPI*)(void*);
using GroupBoolean = HRESULT(WINAPI*)(void*, bool*);
GroupOperation GroupCollapse_Original;
GroupOperation GroupExpand_Original;
GroupOperation GroupUpdateCollapseState_Original;
GroupBoolean GroupIsCollapsed;
GroupBoolean GroupCanExpand_Original;
GroupBoolean GroupCanCollapse_Original;
using IncludeGroupElement = bool(WINAPI*)(void*, IUnknown* const*);
IncludeGroupElement ShouldIncludeGroupElement_Original;

Grouping GroupingForNativeOperation(void* implementation) {
    if (g_unloading || !g_hasStackRules) return Grouping::System;
    auto identity = IdentifyGroupModel(implementation);
    return identity.windows < 2 ? Grouping::System
                                : GroupingFor(*std::atomic_load(&g_rules), identity);
}

bool ApplyGroupDecision(void* implementation, Grouping grouping) {
    bool collapsed = false;
    auto abi = static_cast<void**>(implementation) + 1;
    if (FAILED(GroupIsCollapsed(abi, &collapsed))) return false;
    const bool targetCollapsed = grouping == Grouping::Stack;
    if (collapsed == targetCollapsed) return false;
    if (targetCollapsed) GroupCollapse_Original(implementation);
    else GroupExpand_Original(implementation);
    bool after = collapsed;
    GroupIsCollapsed(abi, &after);
    Wh_Log(L"Native group: collapsed=%d requested=%d actual=%d", collapsed, targetCollapsed, after);
    return true;
}

void WINAPI GroupCollapse_Hook(void* implementation) {
    if (GroupingForNativeOperation(implementation) != Grouping::Separate) {
        GroupCollapse_Original(implementation);
    }
}

void WINAPI GroupExpand_Hook(void* implementation) {
    if (GroupingForNativeOperation(implementation) != Grouping::Stack) {
        GroupExpand_Original(implementation);
    }
}

void WINAPI GroupUpdateCollapseState_Hook(void* implementation) {
    auto grouping = GroupingForNativeOperation(implementation);
    if (grouping == Grouping::System) {
        GroupUpdateCollapseState_Original(implementation);
    } else {
        ApplyGroupDecision(implementation, grouping);
    }
}

bool GroupPolicyMatches(void* self, Grouping expected) {
    if (g_unloading || !g_hasStackRules || *static_cast<void**>(self) != GroupCollapsibleVtable) {
        return false;
    }
    winrt::com_ptr<IUnknown> group;
    if (FAILED(static_cast<IUnknown*>(self)->QueryInterface(kGroupModelInterface, group.put_void())) ||
        !group || *reinterpret_cast<void**>(group.get()) != GroupViewModelDefaultVtable) {
        return false;
    }
    return GroupingForNativeOperation(reinterpret_cast<void**>(group.get()) - 1) == expected;
}

HRESULT WINAPI GroupCanExpand_Hook(void* self, bool* canExpand) {
    HRESULT result = GroupCanExpand_Original(self, canExpand);
    if (SUCCEEDED(result) && *canExpand && GroupPolicyMatches(self, Grouping::Stack)) {
        *canExpand = false;
    }
    return result;
}

HRESULT WINAPI GroupCanCollapse_Hook(void* self, bool* canCollapse) {
    HRESULT result = GroupCanCollapse_Original(self, canCollapse);
    if (SUCCEEDED(result) && *canCollapse && GroupPolicyMatches(self, Grouping::Separate)) {
        *canCollapse = false;
    }
    return result;
}


bool WINAPI ShouldIncludeGroupElement_Hook(void* layout, IUnknown* const* group) {
    bool native = ShouldIncludeGroupElement_Original(layout, group);
    if (!native || g_unloading || !g_hasStackRules || !group || !*group ||
        *reinterpret_cast<void**>(*group) != GroupViewModelDefaultVtable) {
        return native;
    }
    bool collapsed = true;
    if (SUCCEEDED(GroupIsCollapsed(*group, &collapsed)) && !collapsed &&
        GroupingForNativeOperation(reinterpret_cast<void**>(*group) - 1) == Grouping::Separate) {
        // The system's Always mode still measures a single group button,
        // although the per-app policy expanded its existing window models.
        return false;
    }
    return native;
}

using GetHasLabel = HRESULT(WINAPI*)(void*, bool*);
GetHasLabel TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original;
GetHasLabel TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original;

HRESULT WINAPI TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook(
    void* self, bool* hasLabel) {
    HRESULT result =
        TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original(self, hasLabel);
    if (FAILED(result) || g_unloading) {
        return result;
    }
    // The interfaces are adjacent subobjects on the current native taskbar.
    // Bound the backward lookup and reject an unexpected layout.
    void* model = nullptr;
    auto addresses = static_cast<void**>(self);
    for (size_t offset = 0; offset < 32; ++offset) {
        if (addresses[-static_cast<ptrdiff_t>(offset)] == ITaskListWindowViewModel_vftable) {
            model = addresses - offset;
            break;
        }
    }
    if (!model) {
        return result;
    }
    winrt::com_ptr<IUnknown> unknown;
    if (FAILED(ITaskListWindowViewModel_get_TaskItem(model, unknown.put_void())) || !unknown) {
        return result;
    }
    winrt::com_ptr<ITaskItem> item;
    if (FAILED(unknown->QueryInterface(IID_ITaskItem, item.put_void()))) {
        return result;
    }
    winrt::hstring appId;
    HWND window = nullptr;
    item->get_AppId(winrt::put_abi(appId));
    item->get_WindowId(&window);
    auto label = LabelForWindow(appId, window);
    *hasLabel = label != Label::Hide;
    return result;
}

HRESULT WINAPI TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook(
    void* self, bool* hasLabel) {
    HRESULT result =
        TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original(self, hasLabel);
    auto rules = std::atomic_load(&g_rules);
    if (FAILED(result) || g_unloading) return result;

    auto identity = GetGroupIdentity(self);
    auto label = LabelForKey(*rules, identity.appKey);
    if (label == Label::System) label = LabelForKey(*rules, identity.processKey);
    if (label == Label::Hide) {
        *hasLabel = false;
    } else if (identity.windows > 0) {
        *hasLabel = true;
    }
    return result;
}

void* TaskListWindowViewModel_ITaskbarAppItemViewModel_vftable;
void** g_taskListWindowViewModelHasLabelSlot;
void** g_taskListGroupViewModelHasLabelSlot;

// Leaves the slot intact if it doesn't hold the expected value, e.g. if another
// mod patched it.
bool SetVtableSlot(void** slot, void* expected, void* value) {
    DWORD oldProtect;
    if (!VirtualProtect(slot, sizeof(*slot), PAGE_READWRITE, &oldProtect)) {
        return false;
    }

    bool set =
        InterlockedCompareExchangePointer(slot, value, expected) == expected;
    VirtualProtect(slot, sizeof(*slot), oldProtect, &oldProtect);
    return set;
}

// The HasLabel getters are hooked in their vtable slots, since the linker can
// fold a getter with other identical ones. For example, get_HasLabel and
// get_IsRunning of TaskListWindowViewModel both return true, and hooking the
// shared code would hide the running indicator along with the label.
bool HookHasLabelVtableSlots() {
    auto windowVtable =
        (void**)TaskListWindowViewModel_ITaskbarAppItemViewModel_vftable;
    auto groupVtable =
        (void**)TaskListGroupViewModel_ITaskbarAppItemViewModel_vftable;
    auto windowGetHasLabel = (void*)
        TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original;
    auto groupGetHasLabel = (void*)
        TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original;
    if (!windowVtable || !groupVtable || !windowGetHasLabel ||
        !groupGetHasLabel) {
        return false;
    }

    auto windowHook = (void*)
        TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook;
    auto groupHook = (void*)
        TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook;

    // Both vtables are of the same interface. The group getter reads a field,
    // so its address is unique in its vtable, unlike the window getter's. The
    // first 6 slots are of IInspectable.
    for (int i = 6; i < 64; i++) {
        if (groupVtable[i] != groupGetHasLabel) {
            continue;
        }

        if (!SetVtableSlot(&windowVtable[i], windowGetHasLabel, windowHook) ||
            !SetVtableSlot(&groupVtable[i], groupGetHasLabel, groupHook)) {
            Wh_Log(L"Failed to set HasLabel vtable slot %d", i);
            SetVtableSlot(&windowVtable[i], windowHook, windowGetHasLabel);
            return false;
        }

        g_taskListWindowViewModelHasLabelSlot = &windowVtable[i];
        g_taskListGroupViewModelHasLabelSlot = &groupVtable[i];
        return true;
    }

    Wh_Log(L"HasLabel vtable slot not found");
    return false;
}

void UnhookHasLabelVtableSlots() {
    if (g_taskListWindowViewModelHasLabelSlot) {
        SetVtableSlot(
            g_taskListWindowViewModelHasLabelSlot,
            (void*)
                TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook,
            (void*)
                TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original);
        g_taskListWindowViewModelHasLabelSlot = nullptr;
    }

    if (g_taskListGroupViewModelHasLabelSlot) {
        SetVtableSlot(
            g_taskListGroupViewModelHasLabelSlot,
            (void*)
                TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Hook,
            (void*)
                TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original);
        g_taskListGroupViewModelHasLabelSlot = nullptr;
    }
}

using TaskListGroupViewModel_OnPropertyChanged_t =
    void(WINAPI*)(void* pThis, const std::wstring_view& propertyName);
TaskListGroupViewModel_OnPropertyChanged_t
    TaskListGroupViewModel_OnPropertyChanged;

using TaskListGroupViewModel_OnTaskItemsCollectionChanged_t =
    void(WINAPI*)(void* pThis, void* args);
TaskListGroupViewModel_OnTaskItemsCollectionChanged_t
    TaskListGroupViewModel_OnTaskItemsCollectionChanged_Original;
void WINAPI
TaskListGroupViewModel_OnTaskItemsCollectionChanged_Hook(void* pThis,
                                                         void* args) {
    Wh_Log(L">");

    TaskListGroupViewModel_OnTaskItemsCollectionChanged_Original(pThis, args);

    // Excluded programs are matched by the group's windows, but a change of
    // the windows isn't followed by a native HasLabel notification.
    if (!g_unloading) {
        IdentifyGroupModel(pThis);
        if (TaskListGroupViewModel_OnPropertyChanged)
            TaskListGroupViewModel_OnPropertyChanged(pThis, L"HasLabel");
    }
}

using winrt::Windows::UI::Xaml::FrameworkElement;
using winrt::Windows::UI::Xaml::UIElement;
using winrt::Windows::UI::Xaml::XamlRoot;
using winrt::Windows::UI::Xaml::Media::VisualTreeHelper;

using GetContainerViewModel = void*(WINAPI*)(void** output, const UIElement* element);
using NotifyProperty = void(WINAPI*)(void* implementation, const std::wstring_view& propertyName);
GetContainerViewModel GetWindowViewModel;
GetContainerViewModel GetGroupViewModel;
NotifyProperty NotifyWindowProperty;
using winrt::Windows::Foundation::IInspectable;
using winrt::Windows::Foundation::Collections::IObservableVector;
using winrt::Windows::Foundation::Collections::IVector;
using winrt::Windows::Foundation::Collections::IVectorChangedEventArgs;
using ChildVectorChanged = void(WINAPI*)(void*, const IObservableVector<IInspectable>&,
                                         const IVectorChangedEventArgs&);
ChildVectorChanged NotifyGroupVectorChanged;
using TaskbarButtonUpdate = void(WINAPI*)(void*);
using TaskbarButtonSetIcon = void(WINAPI*)(void*, LONG_PTR);
TaskbarButtonUpdate TaskbarButtonUpdate_Original;
GroupBoolean TaskbarButtonHasLabel;
void* TaskbarButtonVtable;
TaskbarButtonSetIcon TaskbarButtonIcon_Original;
void WINAPI TaskbarButtonUpdate_Hook(void*);
void WINAPI TaskbarButtonIcon_Hook(void*, LONG_PTR);

bool ResolveTaskbarSymbols(HMODULE module) {
    WindhawkUtils::SYMBOL_HOOK symbols[] = {
            {
                {LR"(protected: bool __cdecl winrt::Taskbar::implementation::TaskbarCollapsibleLayoutBase<struct winrt::Taskbar::implementation::TaskbarCollapsibleLayoutXamlTraits>::ShouldIncludeGroupElement(struct winrt::Taskbar::TaskListGroupViewModel const &))"},
                &ShouldIncludeGroupElement_Original, ShouldIncludeGroupElement_Hook,
            },
            {
                {LR"(private: void __cdecl winrt::Taskbar::implementation::TaskbarCollapsibleItemsCollection::OnChildVectorChanged(struct winrt::Windows::Foundation::Collections::IObservableVector<struct winrt::Windows::Foundation::IInspectable> const &,struct winrt::Windows::Foundation::Collections::IVectorChangedEventArgs const &))"},
                &NotifyGroupVectorChanged,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListButton,struct winrt::Taskbar::ITaskListButton>::`vftable')"},
                &TaskbarButtonVtable,
                nullptr,
                true,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListButton,struct winrt::Taskbar::ITaskListButton>::get_HasLabel(bool *))"},
                &TaskbarButtonHasLabel,
                nullptr,
                true,
            },
            {
                {LR"(private: void __cdecl winrt::Taskbar::implementation::TaskListButton::UpdateVisualStates(void))"},
                &TaskbarButtonUpdate_Original, TaskbarButtonUpdate_Hook,
            },
            {
                {LR"(public: void __cdecl winrt::Taskbar::implementation::TaskListButton::Icon(struct winrt::Windows::Storage::Streams::IRandomAccessStream))"},
                &TaskbarButtonIcon_Original, TaskbarButtonIcon_Hook,
            },
            {
                {LR"(public: void __cdecl winrt::Taskbar::implementation::TaskListGroupViewModel::Collapse(void))"},
                &GroupCollapse_Original, GroupCollapse_Hook,
            },
            {
                {LR"(public: void __cdecl winrt::Taskbar::implementation::TaskListGroupViewModel::Expand(void))"},
                &GroupExpand_Original, GroupExpand_Hook,
            },
            {
                {LR"(public: void __cdecl winrt::Taskbar::implementation::TaskListGroupViewModel::UpdateCollapseState(void))"},
                &GroupUpdateCollapseState_Original, GroupUpdateCollapseState_Hook,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ITaskListGroupViewModel>::get_IsCollapsed(bool *))"},
                &GroupIsCollapsed,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ICollapsibleTaskbarItemViewModel>::get_CanExpand(bool *))"},
                &GroupCanExpand_Original, GroupCanExpand_Hook,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ICollapsibleTaskbarItemViewModel>::get_CanCollapse(bool *))"},
                &GroupCanCollapse_Original, GroupCanCollapse_Hook,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ICollapsibleTaskbarItemViewModel>::`vftable')"},
                &GroupCollapsibleVtable,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskbarCollapsibleItemsCollection,struct winrt::Windows::Foundation::Collections::IVector<struct winrt::Windows::Foundation::IInspectable> >::`vftable')"},
                &CollectionVectorVtable,
            },
            {
                {LR"(struct winrt::Taskbar::TaskListWindowViewModel __cdecl TryGetItemFromContainer<struct winrt::Taskbar::TaskListWindowViewModel>(struct winrt::Windows::UI::Xaml::UIElement const &))"},
                &GetWindowViewModel,
            },
            {
                {LR"(struct winrt::Taskbar::TaskListGroupViewModel __cdecl TryGetItemFromContainer<struct winrt::Taskbar::TaskListGroupViewModel>(struct winrt::Windows::UI::Xaml::UIElement const &))"},
                &GetGroupViewModel,
            },
            {
                {LR"(private: void __cdecl winrt::Taskbar::implementation::TaskListWindowViewModel::OnPropertyChanged(class std::basic_string_view<wchar_t,struct std::char_traits<wchar_t> > const &))"},
                &NotifyWindowProperty,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ITaskListGroupViewModel>::`vftable')"},
                &GroupViewModelDefaultVtable,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListWindowViewModel,struct winrt::Taskbar::ITaskListWindowViewModel>::`vftable')"},
                &ITaskListWindowViewModel_vftable,
                nullptr,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListWindowViewModel,struct winrt::Taskbar::ITaskListWindowViewModel>::get_TaskItem(void * *))"},
                &ITaskListWindowViewModel_get_TaskItem,
                nullptr,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListWindowViewModel,struct winrt::Taskbar::ITaskbarAppItemViewModel>::get_HasLabel(bool *))"},
                &TaskListWindowViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original,
                nullptr,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ITaskbarAppItemViewModel>::get_HasLabel(bool *))"},
                &TaskListGroupViewModel_ITaskbarAppItemViewModel_get_HasLabel_Original,
                nullptr,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListWindowViewModel,struct winrt::Taskbar::ITaskbarAppItemViewModel>::`vftable')"},
                &TaskListWindowViewModel_ITaskbarAppItemViewModel_vftable,
                nullptr,
            },
            {
                {LR"(const winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ITaskbarAppItemViewModel>::`vftable')"},
                &TaskListGroupViewModel_ITaskbarAppItemViewModel_vftable,
                nullptr,
            },
            {
                {LR"(public: virtual int __cdecl winrt::impl::produce<struct winrt::Taskbar::implementation::TaskListGroupViewModel,struct winrt::Taskbar::ITaskbarAppItemViewModel>::get_TaskGroup(void * *))"},
                &TaskListGroupViewModel_ITaskbarAppItemViewModel_get_TaskGroup,
                nullptr,
            },
            {
                {LR"(private: void __cdecl winrt::Taskbar::implementation::TaskListGroupViewModel::OnPropertyChanged(class std::basic_string_view<wchar_t,struct std::char_traits<wchar_t> > const &))"},
                &TaskListGroupViewModel_OnPropertyChanged,
                nullptr,
            },
            {
                {LR"(public: void __cdecl winrt::Taskbar::implementation::TaskListGroupViewModel::OnTaskItemsCollectionChanged(struct winrt::WindowsUdk::UI::Shell::TaskItemCollectionChangedEventArgs const &))"},
                &TaskListGroupViewModel_OnTaskItemsCollectionChanged_Original,
                TaskListGroupViewModel_OnTaskItemsCollectionChanged_Hook,
                true,
            },
    };
    return WindhawkUtils::HookSymbols(module, symbols, ARRAYSIZE(symbols));
}

using GetTaskbarHost = void*(WINAPI*)(void*, void**);
using ReleaseTaskbarHost = void(WINAPI*)(void*);
void* PrimaryTaskBandVtable;
void* SecondaryTaskBandVtable;
GetTaskbarHost PrimaryTaskBandHost;
GetTaskbarHost SecondaryTaskBandHost;
void* TaskbarHostFrameHeight;
ReleaseTaskbarHost ReleaseTaskbarHostReference;

bool ResolveTaskbarHostSymbols() {
    auto module = GetModuleHandleW(L"taskbar.dll");
    if (!module) module = LoadLibraryExW(L"taskbar.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!module) return false;

    WindhawkUtils::SYMBOL_HOOK symbols[] = {
        {{LR"(const CTaskBand::`vftable'{for `ITaskListWndSite'})"},
         &PrimaryTaskBandVtable},
        {{LR"(const CSecondaryTaskBand::`vftable'{for `ITaskListWndSite'})"},
         &SecondaryTaskBandVtable},
        {{LR"(public: virtual class std::shared_ptr<class TaskbarHost> __cdecl CTaskBand::GetTaskbarHost(void)const )"},
         &PrimaryTaskBandHost},
        {{LR"(public: virtual class std::shared_ptr<class TaskbarHost> __cdecl CSecondaryTaskBand::GetTaskbarHost(void)const )"},
         &SecondaryTaskBandHost},
        {{LR"(public: int __cdecl TaskbarHost::FrameHeight(void)const )"},
         &TaskbarHostFrameHeight},
        {{LR"(public: void __cdecl std::_Ref_count_base::_Decref(void))"},
         &ReleaseTaskbarHostReference},
    };
    return WindhawkUtils::HookSymbols(module, symbols, ARRAYSIZE(symbols));
}

XamlRoot GetTaskbarRoot(HWND tray) {
    WCHAR className[32]{};
    GetClassNameW(tray, className, ARRAYSIZE(className));
    bool primary = wcscmp(className, L"Shell_TrayWnd") == 0;
    HWND bandWindow = primary
        ? static_cast<HWND>(GetPropW(tray, L"TaskbandHWND"))
        : FindWindowExW(tray, nullptr, L"WorkerW", nullptr);
    if (!bandWindow) return nullptr;

    auto band = reinterpret_cast<void**>(GetWindowLongPtrW(bandWindow, 0));
    if (!band) return nullptr;
    const auto expected = primary ? PrimaryTaskBandVtable : SecondaryTaskBandVtable;
    auto getHost = primary ? PrimaryTaskBandHost : SecondaryTaskBandHost;
    void* host[2]{};
    for (size_t offset = 0; offset <= 20; ++offset) {
        if (band[offset] == expected) {
            getHost(band + offset, host);
            break;
        }
    }
    if (!host[1]) return nullptr;

    struct HostReference {
        void* reference;
        ~HostReference() { ReleaseTaskbarHostReference(reference); }
    } reference{host[1]};
    if (!host[0]) return nullptr;

    // TaskbarHost::FrameHeight starts by moving this to its XAML element.
    // Only use the observed x64 encoding, since this is a private layout.
    auto code = static_cast<const BYTE*>(TaskbarHostFrameHeight);
    if (code[0] != 0x48 || code[1] != 0x83 || code[2] != 0xEC ||
        code[4] != 0x48 || code[5] != 0x83 || code[6] != 0xC1 ||
        code[7] == 0 || code[7] > 0x7F) {
        Wh_Log(L"Unsupported TaskbarHost::FrameHeight layout");
        return nullptr;
    }
    auto unknown = *reinterpret_cast<IUnknown**>(static_cast<BYTE*>(host[0]) + code[7]);
    FrameworkElement element = nullptr;
    if (unknown) unknown->QueryInterface(winrt::guid_of<FrameworkElement>(), winrt::put_abi(element));
    return element ? element.XamlRoot() : nullptr;
}

FrameworkElement FindChild(const FrameworkElement& parent, std::wstring_view match, bool byClass = false) {
    if (!parent) return nullptr;
    const int count = VisualTreeHelper::GetChildrenCount(parent);
    for (int i = 0; i < count; ++i) {
        auto child = VisualTreeHelper::GetChild(parent, i).try_as<FrameworkElement>();
        if (child && (byClass ? winrt::get_class_name(child) : child.Name()) == match) {
            return child;
        }
    }
    return nullptr;
}

using winrt::Windows::UI::Xaml::Controls::Grid;
using winrt::Windows::UI::Xaml::Controls::Image;

using winrt::Windows::UI::Xaml::DependencyProperty;
using winrt::Windows::UI::Xaml::Media::CompositeTransform;
constexpr wchar_t kStackGroup[] = L"LixingStackGroup";

FrameworkElement FindButtonPart(const FrameworkElement& root, std::wstring_view name, int depth = 4) {
    if (!root || depth <= 0) return nullptr;
    if (root.Name() == name) return root;
    const int children = VisualTreeHelper::GetChildrenCount(root);
    for (int index = 0; index < children; ++index) {
        auto child = VisualTreeHelper::GetChild(root, index).try_as<FrameworkElement>();
        if (auto plate = FindButtonPart(child, name, depth - 1)) return plate;
    }
    return nullptr;
}

IInspectable SaveStackProperty(const FrameworkElement& element, const DependencyProperty& property) {
    if (auto binding = element.GetBindingExpression(property)) return binding.ParentBinding();
    return element.ReadLocalValue(property);
}

void RestoreStackProperty(const FrameworkElement& element, const DependencyProperty& property,
                          const IInspectable& value) {
    if (auto binding = value.try_as<winrt::Windows::UI::Xaml::Data::Binding>()) {
        element.SetBinding(property, binding);
    } else if (value && value != DependencyProperty::UnsetValue()) {
        element.SetValue(property, value);
    } else {
        element.ClearValue(property);
    }
}

constexpr wchar_t kOriginalIndicatorX[] = L"LixingOriginalIndicatorX";

void RestoreStackIndicator(const Grid& panel, const Grid& stackGroup) {
    const auto key = winrt::box_value(winrt::hstring{kOriginalIndicatorX});
    const auto saved = stackGroup.Resources();
    if (!saved.HasKey(key)) return;
    if (auto indicator = FindChild(panel, L"RunningIndicator")) {
        auto translation = indicator.Translation();
        translation.x = winrt::unbox_value<float>(saved.Lookup(key));
        indicator.Translation(translation);
    }
    saved.Remove(key);
}

void AlignStackIndicator(const Grid& panel, const Grid& stackGroup, double stackCenterX) {
    auto indicator = FindChild(panel, L"RunningIndicator");
    if (!indicator || indicator.ActualWidth() <= 0) return;

    // ActualOffset is in IconPanel's layout coordinates and omits Translation.
    // Leave the native RunningIndicatorStates and their animations in control
    // of the indicator itself. Only move its rendered horizontal position.
    const double nativeCenterX = indicator.ActualOffset().x + indicator.ActualWidth() / 2;
    auto translation = indicator.Translation();
    const float desiredX = static_cast<float>(stackCenterX - nativeCenterX);
    if (translation.x == desiredX) return;

    const auto key = winrt::box_value(winrt::hstring{kOriginalIndicatorX});
    const auto saved = stackGroup.Resources();
    if (!saved.HasKey(key)) saved.Insert(key, winrt::box_value(translation.x));
    translation.x = desiredX;
    indicator.Translation(translation);
    Wh_Log(L"Stack indicator: native=%.2f target=%.2f translation=%.2f width=%.2f",
           nativeCenterX, stackCenterX, desiredX, indicator.ActualWidth());
}

void UpdateStackIcon(const FrameworkElement& button) {
    auto panel = FindChild(button, L"IconPanel").try_as<Grid>();
    if (!panel) return;

    auto stackGroup = FindChild(panel, kStackGroup).try_as<Grid>();
    auto icon = FindChild(panel, L"Icon").try_as<Image>();
    bool stacked = false;
    if (!g_unloading && icon && icon.Source()) {
        auto element = button.as<UIElement>();
        winrt::com_ptr<IUnknown> model;
        GetGroupViewModel(model.put_void(), &element);
        if (model && *reinterpret_cast<void**>(model.get()) == GroupViewModelDefaultVtable) {
            bool collapsed = false;
            auto group = IdentifyGroupModel(reinterpret_cast<void**>(model.get()) - 1);
            stacked = group.windows > 1 && SUCCEEDED(GroupIsCollapsed(model.get(), &collapsed)) && collapsed;
        }
    }

    auto opacity = UIElement::OpacityProperty();
    auto visibility = UIElement::VisibilityProperty();
    const auto originalOpacityKey = winrt::box_value(winrt::hstring{L"LixingOriginalIconOpacity"});
    auto plate = stacked || stackGroup ? FindButtonPart(button, L"MultiWindowElement") : nullptr;
    if (!stacked) {
        uint32_t groupIndex = 0;
        if (stackGroup && panel.Children().IndexOf(stackGroup, groupIndex)) {
            RestoreStackIndicator(panel, stackGroup);
            if (icon) {
                const auto saved = stackGroup.Resources();
                RestoreStackProperty(icon, opacity, saved.HasKey(originalOpacityKey)
                                      ? saved.Lookup(originalOpacityKey) : nullptr);
            }
            if (plate) RestoreStackProperty(plate, visibility, stackGroup.Tag());
            panel.Children().RemoveAt(groupIndex);
        }
        return;
    }

    const double width = std::isfinite(icon.Width()) ? icon.Width() : icon.ActualWidth();
    const double height = std::isfinite(icon.Height()) ? icon.Height() : icon.ActualHeight();
    if (width <= 8 || height <= 8) return;

    const bool inserting = !stackGroup;
    uint32_t iconIndex = 0;
    if (inserting) {
        if (!panel.Children().IndexOf(icon, iconIndex)) return;
        stackGroup = Grid();
        stackGroup.Name(kStackGroup);
        stackGroup.IsHitTestVisible(false);
        stackGroup.RenderTransform(CompositeTransform());
        Image behind;
        behind.Opacity(0.74);
        behind.RenderTransform(CompositeTransform());
        stackGroup.Children().Append(behind);
        stackGroup.Children().Append(Image());
        auto prior = SaveStackProperty(icon, opacity);
        if (prior && prior != DependencyProperty::UnsetValue()) {
            stackGroup.Resources().Insert(originalOpacityKey, prior);
        }
        if (plate) stackGroup.Tag(SaveStackProperty(plate, visibility));
    }

    if (stackGroup.Width() != width) stackGroup.Width(width);
    if (stackGroup.Height() != height) stackGroup.Height(height);
    if (stackGroup.Margin() != icon.Margin()) stackGroup.Margin(icon.Margin());
    if (stackGroup.HorizontalAlignment() != icon.HorizontalAlignment())
        stackGroup.HorizontalAlignment(icon.HorizontalAlignment());
    if (stackGroup.VerticalAlignment() != icon.VerticalAlignment())
        stackGroup.VerticalAlignment(icon.VerticalAlignment());
    if (Grid::GetColumn(stackGroup) != Grid::GetColumn(icon)) Grid::SetColumn(stackGroup, Grid::GetColumn(icon));
    if (Grid::GetRow(stackGroup) != Grid::GetRow(icon)) Grid::SetRow(stackGroup, Grid::GetRow(icon));
    for (const auto& child : stackGroup.Children()) {
        auto image = child.as<Image>();
        if (image.Source() != icon.Source()) image.Source(icon.Source());
        if (image.Stretch() != icon.Stretch()) image.Stretch(icon.Stretch());
    }

    const auto root = icon.XamlRoot();
    const double dpi = root ? root.RasterizationScale() : 1.0;
    auto background = FindButtonPart(button, L"BackgroundElement");
    auto label = FindButtonPart(button, L"LabelControl");
    const bool labeled = label && label.Visibility() == winrt::Windows::UI::Xaml::Visibility::Visible &&
        label.ActualWidth() > 1;
    double anchorOffset = 0;
    double usableWidth = width + 18 / dpi;
    try {
        if (background && background.ActualWidth() > 0 && !labeled) {
            auto centre = background.TransformToVisual(panel).TransformPoint(
                {static_cast<float>(background.ActualWidth() / 2), 0});
            anchorOffset = centre.X - (icon.ActualOffset().x + width / 2);
            usableWidth = std::max(width, background.ActualWidth() - 8 / dpi);
        }
    } catch (const winrt::hresult_error& error) {
        Wh_Log(L"Stack plate coordinates unavailable: 0x%08X", error.code().value);
        background = nullptr;
    }
    const double reveal = std::clamp(usableWidth - width, 0.0, 5.5 / dpi);
    const double scale = std::clamp((usableWidth - reveal) / width, 1.0, 1.06);
    const double spacing = reveal / scale;
    auto backTransform = stackGroup.Children().GetAt(0).as<Image>().RenderTransform().as<CompositeTransform>();
    auto transform = stackGroup.RenderTransform().as<CompositeTransform>();
    const double offset = anchorOffset - spacing / 2;
    if (transform.TranslateX() != offset || transform.ScaleX() != scale) {
        Wh_Log(L"Stack layout: anchor=%s offset=%.2f revealPx=%.2f scale=%.2f plateWidth=%.2f",
               labeled ? L"labelled slot" : background ? L"hover plate" : L"icon slot",
               anchorOffset, reveal * dpi, scale, background ? background.ActualWidth() : 0);
    }
    if (backTransform.TranslateX() != spacing) backTransform.TranslateX(spacing);
    if (transform.CenterX() != (width + spacing) / 2) transform.CenterX((width + spacing) / 2);
    if (transform.CenterY() != height / 2) transform.CenterY(height / 2);
    if (transform.ScaleX() != scale) transform.ScaleX(scale);
    if (transform.ScaleY() != scale) transform.ScaleY(scale);
    if (transform.TranslateX() != offset) transform.TranslateX(offset);
    if (inserting) {
        // Prepare the visible group before hiding the original image.
        panel.Children().InsertAt(iconIndex, stackGroup);
        try {
            icon.Opacity(0);
        } catch (...) {
            panel.Children().RemoveAt(iconIndex);
            throw;
        }
    }
    if (plate && plate.Visibility() != winrt::Windows::UI::Xaml::Visibility::Collapsed)
        plate.Visibility(winrt::Windows::UI::Xaml::Visibility::Collapsed);
    AlignStackIndicator(panel, stackGroup, icon.ActualOffset().x + width / 2 + anchorOffset);
}

void RecordButtonLayout(void** abi, const FrameworkElement& button) {
    if (g_unloading || !TaskbarButtonVtable || *abi != TaskbarButtonVtable || !TaskbarButtonHasLabel)
        return;
    bool hasLabel = false;
    if (FAILED(TaskbarButtonHasLabel(abi, &hasLabel))) return;
    uint32_t flags = 1 | (hasLabel ? 0 : 2);
    std::wstring key;
    std::wstring owner;

    auto element = button.as<UIElement>();
    winrt::com_ptr<IUnknown> model;
    GetWindowViewModel(model.put_void(), &element);
    if (model && *reinterpret_cast<void**>(model.get()) == ITaskListWindowViewModel_vftable) {
        winrt::com_ptr<IUnknown> taskItem;
        if (FAILED(ITaskListWindowViewModel_get_TaskItem(model.get(), taskItem.put_void())) || !taskItem)
            return;
        auto window = GetTaskItemWindow(taskItem.get());
        DWORD pid = 0;
        if (!window || !GetWindowThreadProcessId(window, &pid)) return;
        key = L"layout:window:" + std::to_wstring(reinterpret_cast<uintptr_t>(window));
        owner = std::to_wstring(pid);
    } else {
        model = nullptr;
        GetGroupViewModel(model.put_void(), &element);
        if (!model || *reinterpret_cast<void**>(model.get()) != GroupViewModelDefaultVtable) return;
        auto identity = IdentifyGroupModel(reinterpret_cast<void**>(model.get()) - 1);
        if (identity.appKey.empty() || !identity.windows) return;
        key = L"layout:" + identity.appKey;
        bool collapsed = false;
        if (identity.windows > 1 && SUCCEEDED(GroupIsCollapsed(model.get(), &collapsed))) {
            flags |= 8 | (collapsed ? 4 : 0);
        }
    }
    std::lock_guard lock(g_aliasLock);
    GroupAlias layout{std::move(owner), flags};
    auto found = g_aliases.find(key);
    if (found != g_aliases.end() && found->second == layout) return;
    g_aliases.insert_or_assign(std::move(key), std::move(layout));
    SetEvent(g_aliasChanged);
}

void TryUpdateStackIconFromButton(void* implementation) {
    try {
        winrt::Windows::Foundation::IUnknown unknown;
        winrt::copy_from_abi(unknown, static_cast<void**>(implementation) + 3);
        auto element = unknown.try_as<FrameworkElement>();
        if (element) {
            UpdateStackIcon(element);
            RecordButtonLayout(static_cast<void**>(implementation) + 3, element);
        }
    } catch (const winrt::hresult_error&) {
        // A template may be in the middle of recycling this button.
    }
}

void WINAPI TaskbarButtonUpdate_Hook(void* implementation) {
    TaskbarButtonUpdate_Original(implementation);
    TryUpdateStackIconFromButton(implementation);
}

void WINAPI TaskbarButtonIcon_Hook(void* implementation, LONG_PTR stream) {
    TaskbarButtonIcon_Original(implementation, stream);
    TryUpdateStackIconFromButton(implementation);
}

struct TaskbarGroups {
    IVector<IInspectable> vector{nullptr};
    void* implementation = nullptr;
    std::vector<winrt::com_ptr<IUnknown>> groups;
};

TaskbarGroups GetNativeGroups(const FrameworkElement& frameRepeater) {
    using winrt::Microsoft::UI::Xaml::Controls::ItemsRepeater;
    auto repeater = frameRepeater.try_as<ItemsRepeater>();
    if (!repeater) return {};
    auto source = repeater.ItemsSource();
    auto vector = source ? source.try_as<IVector<IInspectable>>() : nullptr;
    if (!vector) return {};
    auto vectorAbi = static_cast<BYTE*>(winrt::get_abi(vector));
    if (*reinterpret_cast<void**>(vectorAbi) != CollectionVectorVtable) return {};

    // The public vector flattens expanded groups. The native ForEachTaskListGroup
    // in the verified Windows build instead walks the original group entries.
    const auto collection = vectorAbi - 0x18;
    auto first = *reinterpret_cast<BYTE**>(collection + 0x98);
    auto last = *reinterpret_cast<BYTE**>(collection + 0xA0);
    constexpr size_t groupRecordSize = 0x28;
    auto begin = reinterpret_cast<uintptr_t>(first);
    auto end = reinterpret_cast<uintptr_t>(last);
    if (end < begin || (end - begin) % groupRecordSize ||
        (end - begin) / groupRecordSize > 1024) return {};
    TaskbarGroups result{vector, collection, {}};
    if (!first) return result;

    for (auto record = first; record != last; record += groupRecordSize) {
        auto candidate = *reinterpret_cast<IUnknown**>(record);
        winrt::com_ptr<IUnknown> group;
        if (candidate && SUCCEEDED(candidate->QueryInterface(kGroupModelInterface, group.put_void())) &&
            group && *reinterpret_cast<void**>(group.get()) == GroupViewModelDefaultVtable) {
            result.groups.push_back(std::move(group));
        }
    }
    return result;
}

struct GroupVectorReset : winrt::implements<GroupVectorReset, IVectorChangedEventArgs> {
    auto CollectionChange() const {
        return winrt::Windows::Foundation::Collections::CollectionChange::Reset;
    }
    uint32_t Index() const { return 0; }
};

bool RefreshGroupProjection(void* collection, IUnknown* group) {
    IObservableVector<IInspectable> vector = nullptr;
    if (FAILED(group->QueryInterface(winrt::guid_of<IObservableVector<IInspectable>>(),
                                     winrt::put_abi(vector))) || !vector) {
        return false;
    }
    NotifyGroupVectorChanged(collection, vector, winrt::make<GroupVectorReset>());
    Wh_Log(L"Updated projection for one group (%u visual items)", vector.Size());
    return true;
}

struct BoundViewModel {
    winrt::com_ptr<IUnknown> object;
    NotifyProperty notify;
};

bool RefreshTaskbarLabels(HWND tray) {
    auto root = GetTaskbarRoot(tray);
    if (!root) return false;
    auto frame = FindChild(root.Content().try_as<FrameworkElement>(), L"Taskbar.TaskbarFrame", true);
    auto repeater = FindChild(FindChild(frame, L"RootGrid"), L"TaskbarFrameRepeater");
    if (!repeater) return false;

    auto previous = std::atomic_load(&g_precedingRules);
    const Rules defaultRules;
    auto current = std::atomic_load(&g_rules);
    const auto& effective = g_unloading ? defaultRules : *current;
    auto groups = GetNativeGroups(repeater);
    if (!groups.vector && (HasStackRules(*previous) || HasStackRules(effective))) return false;
    bool groupChanged = false;
    for (const auto& group : groups.groups) {
        auto implementation = reinterpret_cast<void**>(group.get()) - 1;
        auto identity = IdentifyGroupModel(implementation);
        auto before = GroupingFor(*previous, identity);
        auto after = GroupingFor(effective, identity);
        if (identity.windows > 1 && before != after) {
            if (after == Grouping::System) {
                GroupUpdateCollapseState_Original(implementation);
            } else {
                ApplyGroupDecision(implementation, after);
            }
            if (!RefreshGroupProjection(groups.implementation, group.get())) return false;
            groupChanged = true;
        }
    }

    // Keep view models alive while notifying bindings. A binding update is
    // allowed to modify the visual tree or reuse a taskbar button.
    std::vector<BoundViewModel> models;
    size_t buttons = 0;
    const int count = VisualTreeHelper::GetChildrenCount(repeater);
    for (int i = 0; i < count; ++i) {
        auto child = VisualTreeHelper::GetChild(repeater, i).try_as<FrameworkElement>();
        if (!child || child.Name() != L"TaskListButton") continue;
        ++buttons;
        try {
            UpdateStackIcon(child);
        } catch (const winrt::hresult_error& error) {
            Wh_Log(L"Stack decoration unavailable: 0x%08X", error.code().value);
        }
        auto element = child.as<UIElement>();
        winrt::com_ptr<IUnknown> model;
        GetWindowViewModel(model.put_void(), &element);
        if (model && *reinterpret_cast<void**>(model.get()) == ITaskListWindowViewModel_vftable) {
            models.push_back({std::move(model), NotifyWindowProperty});
            continue;
        }
        model = nullptr;
        GetGroupViewModel(model.put_void(), &element);
        if (model && *reinterpret_cast<void**>(model.get()) == GroupViewModelDefaultVtable) {
            models.push_back({std::move(model), TaskListGroupViewModel_OnPropertyChanged});
        }
    }
    for (const auto& item : models) {
        // Both default ABI vtables start at offset 8, verified from this
        // Windows build's producer QueryInterface and constructor symbols.
        void* implementation = reinterpret_cast<void**>(item.object.get()) - 1;
        item.notify(implementation, L"HasLabel");
    }
    if (groupChanged) repeater.InvalidateMeasure();
    Wh_Log(L"Updated label bindings for %zu/%zu taskbar buttons", models.size(), buttons);
    return buttons == 0 || !models.empty();
}

std::atomic<HWND> g_refreshTarget;
std::atomic<bool> g_refreshSucceeded;
std::atomic<bool> g_refreshInvoked;
std::atomic<UINT> g_refreshMessage;

LRESULT CALLBACK RefreshOnTaskbarThread(int code, WPARAM wp, LPARAM lp) {
    if (code == HC_ACTION) {
        auto message = reinterpret_cast<const CWPSTRUCT*>(lp);
        if (message->hwnd == g_refreshTarget && message->message == g_refreshMessage &&
            !g_refreshInvoked.exchange(true)) {
            try {
                g_refreshSucceeded = RefreshTaskbarLabels(message->hwnd);
            } catch (const winrt::hresult_error& failure) {
                Wh_Log(L"Taskbar label bindings were unavailable: 0x%08X", failure.code().value);
            }
        }
    }
    return CallNextHookEx(nullptr, code, wp, lp);
}

bool RefreshLabels(HWND tray) {
    DWORD thread = GetWindowThreadProcessId(tray, nullptr);
    if (!thread) return false;
    if (thread == GetCurrentThreadId()) {
        try {
            return RefreshTaskbarLabels(tray);
        } catch (const winrt::hresult_error&) {
            return false;
        }
    }
    if (!g_refreshMessage) {
        g_refreshMessage = RegisterWindowMessageW(L"Lixing.TaskbarLabelPicker.RefreshLabels");
        if (!g_refreshMessage) return false;
    }
    g_refreshTarget = tray;
    g_refreshInvoked = false;
    g_refreshSucceeded = false;
    HHOOK hook = SetWindowsHookExW(WH_CALLWNDPROC, RefreshOnTaskbarThread, nullptr, thread);
    if (!hook) return false;
    // Same-process synchronous invocation keeps the thread hook alive until
    // its stack leaves this module. It carries no cross-process pointers.
    SendMessageW(tray, g_refreshMessage, 0, 0);
    UnhookWindowsHookEx(hook);
    g_refreshTarget = nullptr;
    return g_refreshSucceeded;
}

bool RefreshAllTaskbars(HWND primary) {
    if (!RefreshLabels(primary)) return false;
    bool succeeded = true;
    EnumWindows([](HWND tray, LPARAM status) -> BOOL {
        DWORD owner = 0;
        GetWindowThreadProcessId(tray, &owner);
        WCHAR name[32]{};
        if (owner == GetCurrentProcessId() && GetClassNameW(tray, name, ARRAYSIZE(name)) &&
            wcscmp(name, L"Shell_SecondaryTrayWnd") == 0) {
            if (!RefreshLabels(tray)) *reinterpret_cast<bool*>(status) = false;
        }
        return TRUE;
    }, reinterpret_cast<LPARAM>(&succeeded));
    return succeeded;
}

bool ReadRules(const std::wstring& path, std::vector<wchar_t>& buffer, Rules& rules) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
                              FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    // An empty whitelist is represented by a present, zero-byte file. A
    // transient open failure must retain the rules currently in use.
    if (file == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER length{};
    if (!GetFileSizeEx(file, &length) || length.QuadPart > 65536 ||
        length.QuadPart < 0 || length.QuadPart % sizeof(wchar_t) != 0) {
        CloseHandle(file);
        return false;
    }
    buffer.assign(length.QuadPart / sizeof(wchar_t) + 1, 0);
    DWORD bytes = 0;
    bool read = ReadFile(file, buffer.data(), length.LowPart, &bytes, nullptr);
    CloseHandle(file);
    if (!read || bytes != length.LowPart) return false;
    for (const wchar_t* entry = buffer.data(); *entry; entry += wcslen(entry) + 1) {
        rules.emplace_back(entry);
    }
    return true;
}

bool AcknowledgeRules(const std::wstring& path, const std::vector<wchar_t>& buffer) {
    std::wstring temporary = path + L".tmp";
    HANDLE file = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return false;
    const DWORD size = static_cast<DWORD>(buffer.size() * sizeof(wchar_t));
    DWORD written = 0;
    bool ok = size == 0 || (WriteFile(file, buffer.data(), size, &written, nullptr) && written == size);
    CloseHandle(file);
    if (ok && MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING)) return true;
    DeleteFileW(temporary.c_str());
    return false;
}

DWORD WINAPI WatchRules(LPVOID) {
    WCHAR profile[MAX_PATH];
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_PROFILE, nullptr,
                                SHGFP_TYPE_CURRENT, profile))) {
        return 0;
    }
    auto directory = std::wstring(profile) + LR"(\.lixing\TaskbarLabelPicker\)";
    const auto rulesPath = directory + L"rules.dat";
    const auto appliedPath = directory + L"applied.dat";
    const auto aliasesPath = directory + L"aliases.dat";
    GroupAliases publishedAliases;
    const auto directoryPath = directory.substr(0, directory.size() - 1);
    const DWORD readyAttributes = GetFileAttributesW((directory + L"profile.ready").c_str());
    if (readyAttributes == INVALID_FILE_ATTRIBUTES || (readyAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
        Wh_Log(L"Taskbar configuration is not initialized; run the installed launcher.");
        return 0;
    }
    HANDLE settingsChanged = FindFirstChangeNotificationW(directoryPath.c_str(), FALSE,
        FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_LAST_WRITE);
    if (settingsChanged == INVALID_HANDLE_VALUE) {
        Wh_Log(L"Failed to listen for taskbar rules changes: %lu", GetLastError());
        return 0;
    }
    HANDLE notifications[] = {g_stop, settingsChanged, g_aliasChanged};

    HWND activeTray = nullptr;
    Rules refreshed;
    bool refreshPending = false;
    bool acknowledgementPending = false;
    while (WaitForSingleObject(g_stop, 0) == WAIT_TIMEOUT) {
        auto tray = FindWindowW(L"Shell_TrayWnd", nullptr);
        DWORD owner = 0;
        GetWindowThreadProcessId(tray, &owner);
        if (owner == GetCurrentProcessId()) {
            auto requested = std::make_shared<Rules>();
            std::vector<wchar_t> buffer;
            if (ReadRules(rulesPath, buffer, *requested)) {
                if (activeTray != tray || refreshPending || *requested != refreshed) {
                    std::atomic_store(&g_precedingRules, std::make_shared<const Rules>(refreshed));
                    std::atomic_store(&g_rules, std::shared_ptr<const Rules>(requested));
                    g_hasStackRules = HasStackRules(*requested);
                    refreshPending = !RefreshAllTaskbars(tray);
                    if (refreshPending) {
                        // A newly created taskbar may not have a XAML tree yet.
                        WaitForSingleObject(g_stop, 300);
                        continue;
                    }
                    activeTray = tray;
                    refreshed = *requested;
                    acknowledgementPending = true;
                    SetPropW(activeTray, kTrayProperty, reinterpret_cast<HANDLE>(1));
                }
                if (acknowledgementPending && AcknowledgeRules(appliedPath, buffer)) {
                    acknowledgementPending = false;
                }
            }
        }
        GroupAliases aliases;
        {
            std::lock_guard lock(g_aliasLock);
            std::erase_if(g_aliases, [](const auto& entry) {
                std::wstring_view key = entry.first;
                bool layout = key.starts_with(L"layout:window:");
                if (layout) key.remove_prefix(7);
                if (!key.starts_with(L"window:")) return false;
                auto window = reinterpret_cast<HWND>(std::wcstoull(key.data() + 7, nullptr, 10));
                DWORD pid = 0;
                return !GetWindowThreadProcessId(window, &pid) ||
                    pid != (layout ? std::wcstoul(entry.second.processKey.c_str(), nullptr, 10) : entry.second.windows);
            });
            if (g_aliases != publishedAliases) aliases = g_aliases;
        }
        bool aliasWritePending = false;
        if (!aliases.empty()) {
            std::vector<wchar_t> data;
            for (const auto& [key, alias] : aliases) {
                data.insert(data.end(), key.begin(), key.end());
                data.push_back(0);
                data.insert(data.end(), alias.processKey.begin(), alias.processKey.end());
                data.push_back(0);
                auto count = std::to_wstring(alias.windows);
                data.insert(data.end(), count.begin(), count.end());
                data.push_back(0);
            }
            if (AcknowledgeRules(aliasesPath, data)) publishedAliases = std::move(aliases);
            else aliasWritePending = true;
        }
        const bool pending = !activeTray || refreshPending || acknowledgementPending || aliasWritePending;
        DWORD event = WaitForMultipleObjects(ARRAYSIZE(notifications), notifications, FALSE,
                                             pending ? 300 : INFINITE);
        if (event == WAIT_OBJECT_0 || event == WAIT_FAILED) break;
        if (event == WAIT_OBJECT_0 + 1 && !FindNextChangeNotification(settingsChanged)) break;
    }
    FindCloseChangeNotification(settingsChanged);
    if (activeTray) RemovePropW(activeTray, kTrayProperty);
    return 0;
}

HMODULE GetTaskbarModule() {
    HMODULE module = GetModuleHandleW(L"Taskbar.View.dll");
    return module ? module : GetModuleHandleW(L"ExplorerExtensions.dll");
}

bool PrepareTaskbar(HMODULE module, bool runHooksNow) {
    std::lock_guard lock(g_startLock);
    if (g_unloading) return false;
    if (module && !g_taskbarAttempted) {
        g_taskbarAttempted = true;
        g_taskbarReady = ResolveTaskbarHostSymbols() && ResolveTaskbarSymbols(module) &&
            (!runHooksNow || Wh_ApplyHookOperations()) && HookHasLabelVtableSlots();
    }
    if (g_afterInit && g_taskbarReady && !g_worker) {
        g_worker = CreateThread(nullptr, 0, WatchRules, nullptr, 0, nullptr);
    }
    return g_taskbarReady;
}

decltype(&LoadLibraryExW) g_loadLibrary;

HMODULE WINAPI ObserveModuleLoad(LPCWSTR path, HANDLE file, DWORD flags) {
    HMODULE module = g_loadLibrary(path, file, flags);
    if (!g_taskbarAttempted && module && (flags & (LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE)) == 0 &&
        module == GetTaskbarModule()) {
        PrepareTaskbar(module, true);
    }
    return module;
}

}  // namespace

BOOL Wh_ModInit() {
    auto kernel = GetModuleHandleW(L"kernelbase.dll");
    if (!kernel) return FALSE;
    g_stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!g_stop) return FALSE;
    g_aliasChanged = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!g_aliasChanged) {
        CloseHandle(g_stop);
        g_stop = nullptr;
        return FALSE;
    }
    auto module = GetTaskbarModule();
    bool initialized = module ? PrepareTaskbar(module, false) :
        WindhawkUtils::SetFunctionHook(
            reinterpret_cast<decltype(&LoadLibraryExW)>(GetProcAddress(kernel, "LoadLibraryExW")),
            ObserveModuleLoad, &g_loadLibrary);
    if (!initialized) {
        CloseHandle(g_aliasChanged);
        CloseHandle(g_stop);
        g_stop = nullptr;
    }
    return initialized;
}

void Wh_ModAfterInit() {
    {
        std::lock_guard lock(g_startLock);
        g_afterInit = true;
    }
    PrepareTaskbar(GetTaskbarModule(), true);
}

void Wh_ModBeforeUninit() {
    {
        std::lock_guard lock(g_startLock);
        g_unloading = true;
    }
    SetEvent(g_stop);
    if (g_worker) {
        WaitForSingleObject(g_worker, INFINITE);
        CloseHandle(g_worker);
    }
    UnhookHasLabelVtableSlots();
    if (!std::atomic_load(&g_rules)->empty()) {
        std::atomic_store(&g_precedingRules, std::atomic_load(&g_rules));
        if (auto tray = FindWindowW(L"Shell_TrayWnd", nullptr)) {
            DWORD owner = 0;
            GetWindowThreadProcessId(tray, &owner);
            if (owner == GetCurrentProcessId()) RefreshAllTaskbars(tray);
        }
    }
}

void Wh_ModUninit() {
    CloseHandle(g_aliasChanged);
    CloseHandle(g_stop);
}
