/*
 * Entanglement Explorer overlay: draws the two-rings badge on entangled files.
 *
 * A COM in-process server implementing IShellIconOverlayIdentifier. Explorer asks IsMemberOf()
 * for every file it shows; the answer comes from %APPDATA%\Entanglement\overlay.txt
 * (one absolute path per line, UTF-8), which the Entanglement app keeps up to date.
 * The list is re-read at most once per second, and only when the file changed.
 *
 * Exports:
 *   DllGetClassObject, DllCanUnloadNow           COM
 *   DllRegisterServer, DllUnregisterServer       regsvr32 (HKLM, needs admin)
 *   RefreshW                                     rundll32 EntanglementOverlay.dll,Refresh <path>
 *                                                tells Explorer to redraw one file's icon
 *
 * Build: see build.cmd (MSVC or zig cc). Plain C, no CRT features beyond the Win32 API.
 */
#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <ole2.h>
#include <olectl.h>
#include <shlobj.h>

/* {3BD83F2D-1F11-4C8C-A165-5CE02251907C} – must match the app (ExplorerOverlay.kt). */
static const CLSID CLSID_EntanglementOverlay =
    {0x3bd83f2d, 0x1f11, 0x4c8c, {0xa1, 0x65, 0x5c, 0xe0, 0x22, 0x51, 0x90, 0x7c}};
#define CLSID_TEXT L"{3BD83F2D-1F11-4C8C-A165-5CE02251907C}"

/* Explorer only uses the first 11 third-party overlays, sorted by name; leading spaces sort first. */
#define OVERLAY_NAME L"     Entanglement"
#define OVERLAY_KEY L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\ShellIconOverlayIdentifiers\\" OVERLAY_NAME
#define CLSID_KEY L"Software\\Classes\\CLSID\\" CLSID_TEXT
#define APPROVED_KEY L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Shell Extensions\\Approved"
#define DESCRIPTION L"Entanglement overlay"

static HINSTANCE g_module;
static volatile LONG g_refs; /* live objects + server locks */

/* ------------------------------------------------------------------------------------------------
 * The list of entangled paths
 * --------------------------------------------------------------------------------------------- */

static SRWLOCK g_listLock = SRWLOCK_INIT;
static WCHAR g_listPath[MAX_PATH * 2];
static WCHAR *g_text;      /* lower-cased file content; lines are split in place */
static WCHAR **g_paths;
static int g_count;
static FILETIME g_listTime;
static ULONGLONG g_lastCheck;

static void list_path_init(void)
{
    /* ENTANGLEMENT_OVERLAY_LIST lets tests point at another file; Explorer never has it set. */
    DWORD n = GetEnvironmentVariableW(L"ENTANGLEMENT_OVERLAY_LIST", g_listPath, ARRAYSIZE(g_listPath));
    if (n > 0 && n < ARRAYSIZE(g_listPath)) return;
    g_listPath[0] = 0;
    WCHAR appData[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_APPDATA, NULL, 0, appData))) {
        lstrcpynW(g_listPath, appData, MAX_PATH);
        lstrcatW(g_listPath, L"\\Entanglement\\overlay.txt");
    }
}

static void list_clear(void)
{
    HeapFree(GetProcessHeap(), 0, g_text);
    HeapFree(GetProcessHeap(), 0, g_paths);
    g_text = NULL;
    g_paths = NULL;
    g_count = 0;
}

/* Caller holds g_listLock exclusively. */
static void list_reload_if_changed(void)
{
    ULONGLONG now = GetTickCount64();
    if (g_lastCheck != 0 && now - g_lastCheck < 1000) return;
    g_lastCheck = now;

    WIN32_FILE_ATTRIBUTE_DATA info;
    if (!g_listPath[0] || !GetFileAttributesExW(g_listPath, GetFileExInfoStandard, &info)) {
        list_clear();
        ZeroMemory(&g_listTime, sizeof g_listTime);
        return;
    }
    if (CompareFileTime(&info.ftLastWriteTime, &g_listTime) == 0) return;

    HANDLE file = CreateFileW(g_listPath, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return; /* app is replacing it; try again next second */
    LARGE_INTEGER size;
    char *bytes = NULL;
    DWORD read = 0;
    BOOL ok = GetFileSizeEx(file, &size) && size.QuadPart < 16 * 1024 * 1024;
    if (ok) {
        bytes = HeapAlloc(GetProcessHeap(), 0, (SIZE_T)size.QuadPart + 1);
        ok = bytes && ReadFile(file, bytes, (DWORD)size.QuadPart, &read, NULL);
    }
    CloseHandle(file);
    if (!ok) {
        HeapFree(GetProcessHeap(), 0, bytes);
        return;
    }

    list_clear();
    g_listTime = info.ftLastWriteTime;
    int chars = read ? MultiByteToWideChar(CP_UTF8, 0, bytes, (int)read, NULL, 0) : 0;
    g_text = HeapAlloc(GetProcessHeap(), 0, ((SIZE_T)chars + 1) * sizeof(WCHAR));
    if (!g_text) {
        HeapFree(GetProcessHeap(), 0, bytes);
        return;
    }
    if (chars) MultiByteToWideChar(CP_UTF8, 0, bytes, (int)read, g_text, chars);
    g_text[chars] = 0;
    HeapFree(GetProcessHeap(), 0, bytes);
    if (chars) CharLowerBuffW(g_text, (DWORD)chars);

    int lines = 1;
    for (int i = 0; i < chars; i++) if (g_text[i] == L'\n') lines++;
    g_paths = HeapAlloc(GetProcessHeap(), 0, (SIZE_T)lines * sizeof(WCHAR *));
    if (!g_paths) return;

    WCHAR *start = g_text;
    for (int i = 0; i <= chars; i++) {
        if (g_text[i] == L'\n' || g_text[i] == 0) {
            int len = (int)(&g_text[i] - start);
            if (len > 0 && start[len - 1] == L'\r') len--;
            g_text[i] = 0;
            start[len] = 0;
            if (len > 0) g_paths[g_count++] = start;
            start = &g_text[i + 1];
        }
    }
}

static BOOL is_entangled(LPCWSTR path)
{
    WCHAR lower[MAX_PATH * 2];
    int len = lstrlenW(path);
    if (len <= 0 || len >= (int)ARRAYSIZE(lower)) return FALSE;
    lstrcpynW(lower, path, ARRAYSIZE(lower));
    CharLowerBuffW(lower, (DWORD)len);

    BOOL found = FALSE;
    AcquireSRWLockExclusive(&g_listLock);
    list_reload_if_changed();
    for (int i = 0; i < g_count && !found; i++) found = lstrcmpW(g_paths[i], lower) == 0;
    ReleaseSRWLockExclusive(&g_listLock);
    return found;
}

/* ------------------------------------------------------------------------------------------------
 * IShellIconOverlayIdentifier
 * --------------------------------------------------------------------------------------------- */

typedef struct {
    IShellIconOverlayIdentifier iface;
    LONG refs;
} Overlay;

static HRESULT STDMETHODCALLTYPE Overlay_QueryInterface(IShellIconOverlayIdentifier *self, REFIID riid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IShellIconOverlayIdentifier)) {
        *out = self;
        IShellIconOverlayIdentifier_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE Overlay_AddRef(IShellIconOverlayIdentifier *self)
{
    return (ULONG)InterlockedIncrement(&((Overlay *)self)->refs);
}

static ULONG STDMETHODCALLTYPE Overlay_Release(IShellIconOverlayIdentifier *self)
{
    LONG refs = InterlockedDecrement(&((Overlay *)self)->refs);
    if (refs == 0) {
        HeapFree(GetProcessHeap(), 0, self);
        InterlockedDecrement(&g_refs);
    }
    return (ULONG)refs;
}

static HRESULT STDMETHODCALLTYPE Overlay_IsMemberOf(IShellIconOverlayIdentifier *self, LPCWSTR path, DWORD attributes)
{
    (void)self;
    if (!path || (attributes & FILE_ATTRIBUTE_DIRECTORY)) return S_FALSE;
    return is_entangled(path) ? S_OK : S_FALSE;
}

static HRESULT STDMETHODCALLTYPE Overlay_GetOverlayInfo(IShellIconOverlayIdentifier *self, LPWSTR iconFile, int max,
                                                        int *index, DWORD *flags)
{
    (void)self;
    if (!iconFile || !index || !flags || max <= 0) return E_POINTER;
    /* EntanglementOverlay.ico lives next to this DLL. */
    WCHAR path[MAX_PATH];
    DWORD n = GetModuleFileNameW(g_module, path, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return E_FAIL;
    WCHAR *slash = path + n;
    while (slash > path && *slash != L'\\') slash--;
    *slash = 0;
    if (lstrlenW(path) + 32 >= max) return E_FAIL;
    lstrcpyW(iconFile, path);
    lstrcatW(iconFile, L"\\EntanglementOverlay.ico");
    *index = 0;
    *flags = ISIOI_ICONFILE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Overlay_GetPriority(IShellIconOverlayIdentifier *self, int *priority)
{
    (void)self;
    if (!priority) return E_POINTER;
    *priority = 0; /* highest */
    return S_OK;
}

static IShellIconOverlayIdentifierVtbl g_overlayVtbl = {
    Overlay_QueryInterface, Overlay_AddRef, Overlay_Release,
    Overlay_IsMemberOf, Overlay_GetOverlayInfo, Overlay_GetPriority,
};

/* ------------------------------------------------------------------------------------------------
 * Class factory
 * --------------------------------------------------------------------------------------------- */

static HRESULT STDMETHODCALLTYPE Factory_QueryInterface(IClassFactory *self, REFIID riid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IClassFactory)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE Factory_AddRef(IClassFactory *self) { (void)self; return 2; }
static ULONG STDMETHODCALLTYPE Factory_Release(IClassFactory *self) { (void)self; return 1; }

static HRESULT STDMETHODCALLTYPE Factory_CreateInstance(IClassFactory *self, IUnknown *outer, REFIID riid, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (outer) return CLASS_E_NOAGGREGATION;
    Overlay *overlay = HeapAlloc(GetProcessHeap(), 0, sizeof(Overlay));
    if (!overlay) return E_OUTOFMEMORY;
    overlay->iface.lpVtbl = &g_overlayVtbl;
    overlay->refs = 1;
    InterlockedIncrement(&g_refs);
    HRESULT hr = Overlay_QueryInterface(&overlay->iface, riid, out);
    Overlay_Release(&overlay->iface);
    return hr;
}

static HRESULT STDMETHODCALLTYPE Factory_LockServer(IClassFactory *self, BOOL lock)
{
    (void)self;
    if (lock) InterlockedIncrement(&g_refs);
    else InterlockedDecrement(&g_refs);
    return S_OK;
}

static IClassFactoryVtbl g_factoryVtbl = {
    Factory_QueryInterface, Factory_AddRef, Factory_Release, Factory_CreateInstance, Factory_LockServer,
};
static IClassFactory g_factory = {&g_factoryVtbl};

/* ------------------------------------------------------------------------------------------------
 * DLL exports
 * --------------------------------------------------------------------------------------------- */

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
        list_path_init();
    }
    return TRUE;
}

__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void **out)
{
    if (!out) return E_POINTER;
    *out = NULL;
    if (!IsEqualCLSID(clsid, &CLSID_EntanglementOverlay)) return CLASS_E_CLASSNOTAVAILABLE;
    return Factory_QueryInterface(&g_factory, riid, out);
}

__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void)
{
    return g_refs == 0 ? S_OK : S_FALSE;
}

static LONG set_string(HKEY root, LPCWSTR key, LPCWSTR name, LPCWSTR value)
{
    HKEY h;
    LONG rc = RegCreateKeyExW(root, key, 0, NULL, 0, KEY_WRITE, NULL, &h, NULL);
    if (rc != ERROR_SUCCESS) return rc;
    rc = RegSetValueExW(h, name, 0, REG_SZ, (const BYTE *)value, (DWORD)((lstrlenW(value) + 1) * sizeof(WCHAR)));
    RegCloseKey(h);
    return rc;
}

__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void)
{
    WCHAR dll[MAX_PATH];
    DWORD n = GetModuleFileNameW(g_module, dll, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return SELFREG_E_CLASS;
    if (set_string(HKEY_LOCAL_MACHINE, CLSID_KEY, NULL, DESCRIPTION) ||
        set_string(HKEY_LOCAL_MACHINE, CLSID_KEY L"\\InprocServer32", NULL, dll) ||
        set_string(HKEY_LOCAL_MACHINE, CLSID_KEY L"\\InprocServer32", L"ThreadingModel", L"Apartment") ||
        set_string(HKEY_LOCAL_MACHINE, OVERLAY_KEY, NULL, CLSID_TEXT) ||
        set_string(HKEY_LOCAL_MACHINE, APPROVED_KEY, CLSID_TEXT, DESCRIPTION)) {
        return SELFREG_E_CLASS;
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, NULL, NULL);
    return S_OK;
}

__declspec(dllexport) HRESULT WINAPI DllUnregisterServer(void)
{
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, CLSID_KEY);
    RegDeleteKeyW(HKEY_LOCAL_MACHINE, OVERLAY_KEY);
    HKEY h;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, APPROVED_KEY, 0, KEY_SET_VALUE, &h) == ERROR_SUCCESS) {
        RegDeleteValueW(h, CLSID_TEXT);
        RegCloseKey(h);
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, NULL, NULL);
    return S_OK;
}

/* rundll32 EntanglementOverlay.dll,Refresh "C:\path\file.md" – redraws that file's icon in Explorer. */
__declspec(dllexport) void CALLBACK RefreshW(HWND window, HINSTANCE instance, LPWSTR args, int show)
{
    (void)window; (void)instance; (void)show;
    if (!args) return;
    while (*args == L' ') args++;
    if (*args == L'"') {
        args++;
        WCHAR *end = args;
        while (*end && *end != L'"') end++;
        *end = 0;
    }
    if (*args) SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, args, NULL);
}
