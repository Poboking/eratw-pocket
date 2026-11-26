// eratw-pack-one —— 一键出包（零依赖 Win32 GUI + headless CLI）
//
// 这个工具的存在意义：出包的所有隐性知识都写在这里，调用者不需要读任何文档。
// 它自动完成：定位仓库/工具链 → 同步代码 → 校准 4.981 语料根 → 补齐端点令牌/R2/签名章配置
// → 释放被空闲 daemon 独占的 Gradle 缓存锁 → 兼容档编译随包 erbc → 出包并跑五步体检
// → 失败自动诊断+自愈重试 → 归档对账 →（正式包）发版上传 →（可选）装机并拉起。
//
// 用法：
//   双击 exe                         图形界面，选包型点【开始】
//   exe --mode dev --headless        给 agent 用：命令行返回结果 + 桌面常驻实时进度窗
//   exe --no-window                  CI/无桌面场景：不弹窗（默认任何调用都弹）
//   exe --mode release --github-release   正式包 + R2 镜像 + GitHub Release + 装机，一条命令到底
//   exe --selftest                   只跑预检（不构建、不上传、不装机）
//   exe --root <仓库根>              显式指定仓库根（缺省自动向上找）
//
// 包型：
//   release       正式包：完整包 + 上报 + 更新源 + 发布章 + 归档 + emit_update --upload
//   test-release  测试用正式包：同上，但**不上传**，版本号不变（默认）
//   dev           测试包：--skip-assets --no-update 小包，一分钟级，复用设备上已解包数据

#![allow(non_snake_case, dead_code)]

use std::collections::HashMap;
use std::env;
use std::ffi::c_void;
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime};

// ------------------------------------------------------------------ Win32 FFI

type HWND = isize;
type HINSTANCE = isize;
type HMENU = isize;
type HBRUSH = isize;
type HFONT = isize;
type HICON = isize;
type HCURSOR = isize;
type LRESULT = isize;
type WPARAM = usize;
type LPARAM = isize;
type UINT = u32;
type DWORD = u32;
type BOOL = i32;
type ATOM = u16;

#[repr(C)]
struct WNDCLASSW {
    style: UINT,
    lpfnWndProc: Option<unsafe extern "system" fn(HWND, UINT, WPARAM, LPARAM) -> LRESULT>,
    cbClsExtra: i32,
    cbWndExtra: i32,
    hInstance: HINSTANCE,
    hIcon: HICON,
    hCursor: HCURSOR,
    hbrBackground: HBRUSH,
    lpszMenuName: *const u16,
    lpszClassName: *const u16,
}

#[repr(C)]
struct POINT {
    x: i32,
    y: i32,
}

#[repr(C)]
struct MSG {
    hwnd: HWND,
    message: UINT,
    wParam: WPARAM,
    lParam: LPARAM,
    time: DWORD,
    pt: POINT,
}

#[link(name = "user32")]
extern "system" {
    fn RegisterClassW(lpWndClass: *const WNDCLASSW) -> ATOM;
    fn CreateWindowExW(
        dwExStyle: DWORD,
        lpClassName: *const u16,
        lpWindowName: *const u16,
        dwStyle: DWORD,
        x: i32,
        y: i32,
        nWidth: i32,
        nHeight: i32,
        hWndParent: HWND,
        hMenu: HMENU,
        hInstance: HINSTANCE,
        lpParam: *const c_void,
    ) -> HWND;
    fn ShowWindow(hWnd: HWND, nCmdShow: i32) -> BOOL;
    fn UpdateWindow(hWnd: HWND) -> BOOL;
    fn GetMessageW(lpMsg: *mut MSG, hWnd: HWND, wMsgFilterMin: UINT, wMsgFilterMax: UINT) -> BOOL;
    fn TranslateMessage(lpMsg: *const MSG) -> BOOL;
    fn DispatchMessageW(lpMsg: *const MSG) -> LRESULT;
    fn DefWindowProcW(hWnd: HWND, Msg: UINT, wParam: WPARAM, lParam: LPARAM) -> LRESULT;
    fn SendMessageW(hWnd: HWND, Msg: UINT, wParam: WPARAM, lParam: LPARAM) -> LRESULT;
    fn PostMessageW(hWnd: HWND, Msg: UINT, wParam: WPARAM, lParam: LPARAM) -> BOOL;
    fn PostQuitMessage(nExitCode: i32);
    fn DestroyWindow(hWnd: HWND) -> BOOL;
    fn MessageBoxW(hWnd: HWND, lpText: *const u16, lpCaption: *const u16, uType: UINT) -> i32;
    fn SetWindowTextW(hWnd: HWND, lpString: *const u16) -> BOOL;
    fn GetWindowTextW(hWnd: HWND, lpString: *mut u16, nMaxCount: i32) -> i32;
    fn GetDlgItem(hWnd: HWND, nIDDlgItem: i32) -> HWND;
    fn EnableWindow(hWnd: HWND, bEnable: BOOL) -> BOOL;
    fn LoadCursorW(hInstance: HINSTANCE, lpCursorName: *const u16) -> HCURSOR;
    fn SetTimer(hWnd: HWND, nIDEvent: WPARAM, uElapse: UINT, lpTimerFunc: *const c_void) -> WPARAM;
    fn KillTimer(hWnd: HWND, nIDEvent: WPARAM) -> BOOL;
}

#[repr(C)]
struct STARTUPINFOW {
    cb: DWORD,
    lpReserved: *mut u16,
    lpDesktop: *mut u16,
    lpTitle: *mut u16,
    dwX: DWORD,
    dwY: DWORD,
    dwXSize: DWORD,
    dwYSize: DWORD,
    dwXCountChars: DWORD,
    dwYCountChars: DWORD,
    dwFillAttribute: DWORD,
    dwFlags: DWORD,
    wShowWindow: u16,
    cbReserved2: u16,
    lpReserved2: *mut u8,
    hStdInput: isize,
    hStdOutput: isize,
    hStdError: isize,
}

#[repr(C)]
struct PROCESS_INFORMATION {
    hProcess: isize,
    hThread: isize,
    dwProcessId: DWORD,
    dwThreadId: DWORD,
}

#[link(name = "kernel32")]
extern "system" {
    fn GetModuleHandleW(lpModuleName: *const u16) -> HINSTANCE;
    fn FreeConsole() -> BOOL;
    // 关键：Rust 的 Command::spawn 在 Windows 用 bInheritHandles=TRUE，会让观察窗
    // 继承 CLI 的 stdout 管道写端 —— agent 的 `| tail` 永远等不到 EOF。
    // 自己调 CreateProcessW 并传 FALSE，才能既弹窗又让命令行立刻返回。
    fn CreateProcessW(
        lpApplicationName: *const u16,
        lpCommandLine: *mut u16,
        lpProcessAttributes: *const c_void,
        lpThreadAttributes: *const c_void,
        bInheritHandles: BOOL,
        dwCreationFlags: DWORD,
        lpEnvironment: *const c_void,
        lpCurrentDirectory: *const u16,
        lpStartupInfo: *const STARTUPINFOW,
        lpProcessInformation: *mut PROCESS_INFORMATION,
    ) -> BOOL;
    fn CloseHandle(hObject: isize) -> BOOL;
    fn GetLastError() -> DWORD;
    // 跨进程互斥：dwShareMode=0 的独占句柄，进程退出时内核自动释放。
    // 比「写 PID 文件」可靠 —— 崩溃/被强杀都不会留下假锁。
    fn CreateFileW(
        lpFileName: *const u16,
        dwDesiredAccess: DWORD,
        dwShareMode: DWORD,
        lpSecurityAttributes: *const c_void,
        dwCreationDisposition: DWORD,
        dwFlagsAndAttributes: DWORD,
        hTemplateFile: isize,
    ) -> isize;
}

#[link(name = "gdi32")]
extern "system" {
    fn GetStockObject(fnIndex: i32) -> HFONT;
}

#[link(name = "comctl32")]
extern "system" {
    fn InitCommonControls();
}

fn wide(s: &str) -> Vec<u16> {
    let mut v: Vec<u16> = s.encode_utf16().collect();
    v.push(0);
    v
}

fn w(s: &str) -> *const u16 {
    // 仅供创建控件/窗口时临时使用：调用前把 wide() 的结果绑在变量上，别直接用这个。
    // 这里用 Box 泄漏换取稳定的指针，生命周期覆盖整次调用。
    Box::leak(wide(s).into_boxed_slice()).as_ptr()
}

// ------------------------------------------------------------------- 常量

const WM_APP: UINT = 0x8000;
const WM_TIMER: UINT = 0x0113;
const WM_APP_LOG: UINT = WM_APP + 1;
const WM_APP_STAGE: UINT = WM_APP + 2;
const WM_APP_DONE: UINT = WM_APP + 3;
const WM_COMMAND: UINT = 0x0111;
const WM_DESTROY: UINT = 0x0002;
const WM_CLOSE: UINT = 0x0010;
const WM_SETFONT: UINT = 0x0030;
const EM_SETSEL: UINT = 0x00B1;
const EM_REPLACESEL: UINT = 0x00C2;
const EM_LIMITTEXT: UINT = 0x00C5;
const BM_GETCHECK: UINT = 0x00F0;
const BM_SETCHECK: UINT = 0x00F1;
const PBM_SETPOS: UINT = 0x0402;
const PBM_SETRANGE32: UINT = 0x0406;
const BN_CLICKED: u32 = 0;
const BST_CHECKED: isize = 1;
const MB_OK: UINT = 0x0000;
const MB_YESNO: UINT = 0x0004;
const MB_ICONINFORMATION: UINT = 0x0040;
const MB_ICONWARNING: UINT = 0x0030;
const MB_TOPMOST: UINT = 0x00040000;
const IDYES: i32 = 6;
const SW_SHOWNORMAL: i32 = 1;
const DEFAULT_GUI_FONT: i32 = 17;

const WS_OVERLAPPEDWINDOW: DWORD = 0x00CF0000;
const WS_VISIBLE: DWORD = 0x10000000;
const WS_CHILD: DWORD = 0x40000000;
const WS_TABSTOP: DWORD = 0x00010000;
const WS_BORDER: DWORD = 0x00800000;
const WS_VSCROLL: DWORD = 0x00200000;
const WS_EX_CLIENTEDGE: DWORD = 0x00000200;
const ES_MULTILINE: DWORD = 0x0004;
const ES_READONLY: DWORD = 0x0800;
const ES_AUTOVSCROLL: DWORD = 0x0040;
const ES_AUTOHSCROLL: DWORD = 0x0080;
const BS_PUSHBUTTON: DWORD = 0x0000;
const BS_AUTOCHECKBOX: DWORD = 0x0003;
const BS_AUTORADIOBUTTON: DWORD = 0x0009;
const PBS_SMOOTH: DWORD = 0x0001;

const ID_START: i32 = 1001;
const ID_CANCEL: i32 = 1002;
const ID_OPEN: i32 = 1003;
const ID_MODE_RELEASE: i32 = 1010;
const ID_MODE_TEST: i32 = 1011;
const ID_MODE_DEV: i32 = 1012;
const ID_CHK_GIT: i32 = 1020;
const ID_CHK_INSTALL: i32 = 1021;
const ID_CHK_FORCE_ERBC: i32 = 1022;
const ID_CHK_UPLOAD: i32 = 1023;
const ID_EDIT_NOTE: i32 = 1030;
const ID_EDIT_VERSION: i32 = 1031;
const ID_EDIT_LOG: i32 = 1040;
const ID_PROGRESS: i32 = 1050;
const ID_STAGE: i32 = 1060;
const ID_STATUS: i32 = 1061;

static CANCEL: AtomicBool = AtomicBool::new(false);
static TIMED_OUT: AtomicBool = AtomicBool::new(false);
static CUR_PID: AtomicU32 = AtomicU32::new(0);
static RUNNING: AtomicBool = AtomicBool::new(false);
static LAST_ARCHIVE: Mutex<Option<String>> = Mutex::new(None);

// agent（headless）调用时，CLI 进程把日志写盘，另起一个独立「观察窗」进程按字节增量跟随。
// 两进程解耦：agent 命令行立刻拿退出码，窗口留在桌面持续刷新、跑完也不自动关闭。
struct FollowState {
    path: PathBuf,
    offset: u64,
    pending: String,
    done: bool,
}
static FOLLOW: Mutex<Option<FollowState>> = Mutex::new(None);

const PROXY_KEYS: &[&str] = &[
    "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY",
    "no_proxy",
];
const SIGN_KEYS: &[&str] = &[
    "eratwReleaseStoreFile",
    "eratwReleaseStorePassword",
    "eratwReleaseKeyAlias",
    "eratwReleaseKeyPassword",
];
const R2_KEYS: &[&str] = &["r2_access_key_id", "r2_secret_access_key", "r2_account_id"];
const PACKAGE_NAME: &str = "com.eratw.port.android";
const GITHUB_REPO_DEFAULT: &str = "Poboking/eratw-pocket";
const TIMER_ID: WPARAM = 1;

// ------------------------------------------------------------------ 数据结构

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mode {
    Release,
    TestRelease,
    Dev,
}

impl Mode {
    fn label(self) -> &'static str {
        match self {
            Mode::Release => "正式包（会上传线上更新清单）",
            Mode::TestRelease => "测试用正式包（不上传，版本号不变）",
            Mode::Dev => "测试包（小包，复用设备已解包数据）",
        }
    }

    fn short(self) -> &'static str {
        match self {
            Mode::Release => "正式包",
            Mode::TestRelease => "测试用正式包",
            Mode::Dev => "测试包",
        }
    }

    fn slug(self) -> &'static str {
        match self {
            Mode::Release => "release",
            Mode::TestRelease => "test-release",
            Mode::Dev => "dev",
        }
    }

    /// `allow_degraded` 由降级清单在册条数决定：修干净了就不带放行开关，
    /// 让 pack_release 的 degraded-slices 闸自然要求 0。
    fn pack_args(self, allow_degraded: bool) -> Vec<String> {
        let mut a = Vec::new();
        if allow_degraded {
            a.push("--allow-degraded-corpus".to_string());
        }
        if self == Mode::Dev {
            a.push("--skip-assets".to_string());
            a.push("--no-update".to_string());
        }
        a
    }
}

#[derive(Clone)]
struct Opts {
    mode: Mode,
    sync_git: bool,
    install: bool,
    upload: bool,
    force_erbc: bool,
    note: String,
    bump_version: String,
    confirm_upload: bool,
    publish_only: bool,
    headless: bool,
    selftest: bool,
    root_override: String,
    github_release: bool,
    github_repo: String,
    notes_file: String,
    no_window: bool,
    follow_gui: String,
    follow_title: String,
    device: String,
    timeout_secs: u64,
}

impl Default for Opts {
    fn default() -> Self {
        Self {
            mode: Mode::TestRelease,
            sync_git: true,
            install: true,
            upload: true,
            force_erbc: false,
            note: String::new(),
            bump_version: String::new(),
            confirm_upload: false,
            publish_only: false,
            headless: false,
            selftest: false,
            root_override: String::new(),
            github_release: false,
            github_repo: GITHUB_REPO_DEFAULT.to_string(),
            notes_file: String::new(),
            no_window: false,
            follow_gui: String::new(),
            follow_title: String::new(),
            device: String::new(),
            timeout_secs: 45 * 60,
        }
    }
}

#[derive(Clone)]
struct Emitter {
    hwnd: HWND,
    headless: bool,
    log_path: PathBuf,
}

impl Emitter {
    fn log_only(&self, text: &str) {
        // 日志会被 agent 整段贴回上下文：任何凭据形态先脱敏再落盘/显示。
        let text = redact(text);
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&self.log_path) {
            let _ = writeln!(f, "{}", text);
        }
        if !self.headless && self.hwnd != 0 {
            unsafe {
                let p: *mut String = Box::into_raw(Box::new(text.clone()));
                if PostMessageW(self.hwnd, WM_APP_LOG, 0, p as LPARAM) == 0 {
                    drop(Box::from_raw(p));
                }
            }
        }
    }

    fn line(&self, text: &str) {
        if self.headless {
            println!("{}", redact(text));
        }
        self.log_only(text);
    }

    fn stage(&self, pct: usize, text: &str) {
        self.line(&format!(">>> [{:3}%] {}", pct, text));
        if !self.headless {
            unsafe {
                let p: *mut String = Box::into_raw(Box::new(text.to_string()));
                if PostMessageW(self.hwnd, WM_APP_STAGE, pct as WPARAM, p as LPARAM) == 0 {
                    drop(Box::from_raw(p));
                }
            }
        }
    }
}

struct Wf {
    em: Emitter,
    root: PathBuf,          // 代码根（可以是 git worktree）
    app: PathBuf,           // root/eratw-android
    tooling_root: PathBuf,  // 共享 .tooling/ 所在根（worktree 里没有，需另找）
    jdk_dir: PathBuf,
    gradle_bin: PathBuf,
    gradle_home: PathBuf,
    sdk_dir: PathBuf,
    opts: Opts,
    python: String,
    gradle_bat: PathBuf,
    adb: PathBuf,
    game_root: PathBuf,
    erbc_dir: PathBuf,
    git7: String,
    version: String,
    version_name: String,
    version_code: i64,
    apk_path: PathBuf,
    apk_sha: String,
    online_ok: bool,
    install_ok: bool,
    checks: Vec<String>,
    code_changed: bool,
    started: Instant,
    github_url: String,
    _lock: Option<RunGuard>,
}

impl Wf {
    fn env(&self) -> Vec<(String, String)> {
        let slash = |p: &Path| p.to_string_lossy().replace('\\', "/");
        let mut path = format!("{};{};", slash(&self.jdk_dir.join("bin")), slash(&self.gradle_bin));
        path.push_str(&env::var("PATH").unwrap_or_default());
        vec![
            ("JAVA_HOME".into(), slash(&self.jdk_dir)),
            ("GRADLE_USER_HOME".into(), slash(&self.gradle_home)),
            ("ANDROID_HOME".into(), slash(&self.sdk_dir)),
            ("ANDROID_SDK_ROOT".into(), slash(&self.sdk_dir)),
            ("ERATW_GAMEDIR".into(), slash(&self.game_root)),
            ("ERATW_CORPUS".into(), slash(&self.game_root.join("ERB"))),
            ("ERATW_ERBC_DIR".into(), slash(&self.erbc_dir)),
            ("PATH".into(), path),
            ("PYTHONUTF8".into(), "1".into()),
            ("PYTHONIOENCODING".into(), "utf-8".into()),
            ("PYTHONUNBUFFERED".into(), "1".into()),
            // erbc 编译器（JavaExec）的中文输出按系统缺省编码写管道会被 utf8_lossy 打成乱码，
            // 显式钉死 JVM 的 stdout/stderr 编码（已有同名变量则保留用户设置）。
            ("JAVA_TOOL_OPTIONS".into(), {
                let cur = env::var("JAVA_TOOL_OPTIONS").unwrap_or_default();
                if cur.contains("file.encoding") { cur }
                else { format!("{} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8", cur).trim().to_string() }
            }),
        ]
    }
}

// ------------------------------------------------------------------- 小工具

fn now_stamp() -> String {
    // 只用于日志文件名，精确到秒足够；时区用本地时间的朴素表示。
    let secs = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_secs();
    let (y, mo, d, h, mi, s) = civil_from_epoch(secs);
    format!("{:04}{:02}{:02}-{:02}{:02}{:02}", y, mo, d, h, mi, s)
}

fn civil_from_epoch(secs: u64) -> (i64, u32, u32, u32, u32, u32) {
    // UTC+8（本机口径），仅用于命名，不参与任何判定。
    let t = secs as i64 + 8 * 3600;
    let days = t.div_euclid(86400);
    let rem = t.rem_euclid(86400);
    let h = (rem / 3600) as u32;
    let mi = ((rem % 3600) / 60) as u32;
    let s = (rem % 60) as u32;
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = mp + if mp < 10 { 3 } else { -9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y, m as u32, d as u32, h, mi, s)
}

fn read_text(p: &Path) -> String {
    fs::read_to_string(p).unwrap_or_default()
}

fn parse_props(p: &Path) -> HashMap<String, String> {
    let mut m = HashMap::new();
    for line in read_text(p).lines() {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') || t.starts_with('!') {
            continue;
        }
        if let Some((k, v)) = t.split_once('=') {
            m.insert(k.trim().to_string(), v.trim().to_string());
        }
    }
    m
}

fn placeholder(v: &str) -> bool {
    let t = v.trim().to_ascii_lowercase();
    t.is_empty() || t.starts_with('<') || t.contains("your_") || t.contains("changeme")
        || t == "todo" || t == "xxx" || t == "-" || t.contains("example")
}

fn has_keys(p: &Path, keys: &[&str]) -> bool {
    if !p.is_file() {
        return false;
    }
    let m = parse_props(p);
    keys.iter().all(|k| m.get(*k).map(|v| !placeholder(v)).unwrap_or(false))
}

fn find_props_source(root: &Path, name: &str, keys: &[&str]) -> Option<PathBuf> {
    let mut cands: Vec<PathBuf> = vec![root.join(name), root.join("eratw-android").join(name)];
    if let Some(parent) = root.parent() {
        if let Ok(rd) = fs::read_dir(parent) {
            for e in rd.flatten().take(60) {
                let d = e.path();
                if d.is_dir() && d != root {
                    cands.push(d.join(name));
                    cands.push(d.join("eratw-android").join(name));
                }
            }
        }
    }
    cands.into_iter().find(|p| has_keys(p, keys))
}

fn win_slash(p: &Path) -> String {
    p.to_string_lossy().replace('\\', "/")
}

fn newest_mtime_below(p: &Path, depth: usize) -> SystemTime {
    let mut best = SystemTime::UNIX_EPOCH;
    if !p.is_dir() {
        if let Ok(md) = fs::metadata(p) {
            if let Ok(t) = md.modified() {
                return t;
            }
        }
        return best;
    }
    if let Ok(rd) = fs::read_dir(p) {
        for e in rd.flatten() {
            let q = e.path();
            let t = if q.is_dir() {
                if depth == 0 {
                    continue;
                }
                newest_mtime_below(&q, depth - 1)
            } else {
                fs::metadata(&q).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH)
            };
            if t > best {
                best = t;
            }
        }
    }
    best
}

fn find_program_erbc(app: &Path) -> Option<PathBuf> {
    let base = app.join("tools").join("erbcc").join("build").join("erbc-out");
    let mut stack = vec![base];
    while let Some(d) = stack.pop() {
        let candidate = d.join("program.erbc");
        if candidate.is_file() {
            return Some(candidate);
        }
        if let Ok(rd) = fs::read_dir(&d) {
            for e in rd.flatten() {
                if e.path().is_dir() {
                    stack.push(e.path());
                }
            }
        }
    }
    None
}

fn check_cancel() -> Result<(), String> {
    if TIMED_OUT.load(Ordering::SeqCst) {
        return Err("子进程超时：已自动终止（可用 ERATW_PACK_TIMEOUT_MIN=<分钟> 调大，0 = 不限时）".to_string());
    }
    if CANCEL.load(Ordering::SeqCst) {
        return Err("用户取消".to_string());
    }
    Ok(())
}

fn pump<R: Read + Send + 'static>(r: R, em: Emitter, sink: Arc<Mutex<String>>) {
    let mut br = BufReader::new(r);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match br.read_until(b'\n', &mut buf) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        let line = String::from_utf8_lossy(&buf)
            .trim_end_matches(|c| c == '\r' || c == '\n')
            .to_string();
        if line.trim().is_empty() {
            continue;
        }
        em.line(&line);
        let mut s = sink.lock().unwrap();
        s.push_str(&line);
        s.push('\n');
        if s.len() > 400_000 {
            // 直接按字节切会在 UTF-8 汉字中间 panic：必须对齐字符边界。
            let mut cut = s.len() - 200_000;
            while cut < s.len() && !s.is_char_boundary(cut) { cut += 1; }
            *s = s[cut..].to_string();
        }
    }
}

fn execute(wf: &Wf, program: &str, args: &[String], cwd: &Path) -> Result<(i32, String), String> {
    execute_ex(wf, program, args, cwd, true)
}

fn execute_ex(wf: &Wf, program: &str, args: &[String], cwd: &Path, strip_proxy: bool) -> Result<(i32, String), String> {
    check_cancel()?;
    wf.em.line(&format!("$ {} {}", program, args.join(" ")));
    let mut c = Command::new(program);
    c.args(args).current_dir(cwd).stdout(Stdio::piped()).stderr(Stdio::piped());
    for (k, v) in wf.env() { c.env(k, v); }
    if strip_proxy {
        for k in PROXY_KEYS { c.env_remove(k); }
    }
    let mut child = c.spawn().map_err(|e| format!("无法启动 {}：{}", program, e))?;
    CUR_PID.store(child.id(), Ordering::SeqCst);
    let out = child.stdout.take().unwrap();
    let err = child.stderr.take().unwrap();
    let sink = Arc::new(Mutex::new(String::new()));
    let done = Arc::new(AtomicBool::new(false));
    // 看门狗：Gradle / adb / gh 卡死时不能让 agent 无限等。默认 45 分钟，
    // 通过 ERATW_PACK_TIMEOUT_MIN 调整（0 = 不限时）。done 一置位线程立即退出。
    if wf.opts.timeout_secs > 0 {
        let d = done.clone();
        let pid = child.id();
        let em = wf.em.clone();
        let secs = wf.opts.timeout_secs;
        let pname = program.to_string();
        thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(secs);
            loop {
                let left = deadline.saturating_duration_since(Instant::now());
                if left.is_zero() { break; }
                thread::sleep(left.min(Duration::from_secs(5)));
                if d.load(Ordering::SeqCst) { return; }
            }
            if d.load(Ordering::SeqCst) { return; }
            em.line(&format!("⏱ {} 超过 {} 分钟无响应，自动终止（ERATW_PACK_TIMEOUT_MIN 可调）", pname, secs / 60));
            TIMED_OUT.store(true, Ordering::SeqCst);
            CANCEL.store(true, Ordering::SeqCst);
            let _ = Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"]).output();
        });
    }
    let t1 = thread::spawn({ let em = wf.em.clone(); let s = sink.clone(); move || pump(out, em, s) });
    let t2 = thread::spawn({ let em = wf.em.clone(); let s = sink.clone(); move || pump(err, em, s) });
    let _ = t1.join();
    let _ = t2.join();
    let st = child.wait().map_err(|e| format!("等待子进程失败：{}", e))?;
    done.store(true, Ordering::SeqCst);
    CUR_PID.store(0, Ordering::SeqCst);
    if CANCEL.load(Ordering::SeqCst) {
        return Err(check_cancel().unwrap_err());
    }
    let code = st.code().unwrap_or(-1);
    wf.em.line(&format!("[exit {}] {}", code, program));
    let combined = sink.lock().unwrap().clone();
    Ok((code, combined))
}

fn run_ok(wf: &Wf, program: &str, args: &[String], cwd: &Path) -> Result<String, String> {
    let (code, out) = execute(wf, program, args, cwd)?;
    if code == 0 {
        Ok(out)
    } else {
        Err(format!("{} 退出码 {}；末尾输出：\n{}", program, code, tail(&out, 20)))
    }
}

fn tail(s: &str, n: usize) -> String {
    let lines: Vec<&str> = s.lines().collect();
    let from = lines.len().saturating_sub(n);
    lines[from..].join("\n")
}

fn sargs(a: &[&str]) -> Vec<String> {
    a.iter().map(|s| s.to_string()).collect()
}


// ------------------------------------------------- 边界防护：脱敏 / 互斥 / 工具链 / 对账

fn redact(s: &str) -> String {
    const KEYS: &[&str] = &[
        "token=", "password=", "secret=", "access_key=", "secret_access_key=",
        "x-amz-signature=", "authorization:", "apikey=", "api_key=", "bearer ",
    ];
    let mut out = String::with_capacity(s.len());
    for line in s.split_inclusive('\n') {
        let low = line.to_ascii_lowercase();
        let mut hit = false;
        for k in KEYS {
            if let Some(pos) = low.find(k) {
                let end_key = pos + k.len();
                let rest = &line[end_key..];
                let cut = rest
                    .find(|c: char| c.is_whitespace() || c == '&' || c == '"' || c == ',' || c == '\'')
                    .unwrap_or(rest.len());
                out.push_str(&line[..end_key]);
                out.push_str("<redacted>");
                out.push_str(&rest[cut..]);
                hit = true;
                break;
            }
        }
        if !hit {
            out.push_str(line);
        }
    }
    out
}

struct RunGuard(isize);
impl Drop for RunGuard {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0); }
    }
}

fn acquire_run_lock(path: &Path) -> Result<RunGuard, String> {
    let name = wide(&win_slash(path));
    unsafe {
        let h = CreateFileW(name.as_ptr(), 0x40000000, 0, std::ptr::null(), 2, 0x80, 0);
        if h == 0 || h == -1 {
            return Err(format!(
                "已有另一次出包在运行（共享锁 {} 被独占，err={}）。\n同一 Gradle 缓存不支持并行出包；等上一次跑完再试。\n若上次是异常退出且确认没有进程在跑，删掉这个 0 字节锁文件即可。",
                path.display(), GetLastError()
            ));
        }
        Ok(RunGuard(h))
    }
}

fn cmp_version(a: &str, b: &str) -> std::cmp::Ordering {
    let pa: Vec<u64> = a.split('.').map(|x| x.parse().unwrap_or(0)).collect();
    let pb: Vec<u64> = b.split('.').map(|x| x.parse().unwrap_or(0)).collect();
    for i in 0..pa.len().max(pb.len()) {
        let x = *pa.get(i).unwrap_or(&0);
        let y = *pb.get(i).unwrap_or(&0);
        if x != y {
            return x.cmp(&y);
        }
    }
    std::cmp::Ordering::Equal
}

fn has_code_marker(c: &Path) -> bool {
    c.join("eratw-android").join("devtools").join("pack_release.py").is_file()
}

fn pick_sub(base: &Path, prefix: &str, must: &str) -> Option<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(base).ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.is_dir()
                && p.file_name().unwrap_or_default().to_string_lossy().starts_with(prefix)
                && p.join(must).is_file()
        })
        .collect();
    v.sort();
    v.pop()
}

struct ToolChain {
    jdk: PathBuf,
    gradle_bin: PathBuf,
    gradle_home: PathBuf,
    sdk: PathBuf,
    gradle_bat: PathBuf,
    adb: PathBuf,
}

fn detect_toolchain(tr: &Path) -> Result<ToolChain, String> {
    let t = tr.join(".tooling");
    let jdk = pick_sub(&t, "jdk-", "bin/java.exe")
        .unwrap_or_else(|| t.join("jdk-17.0.20.1+1"));
    let gd = pick_sub(&t, "gradle-", "bin/gradle.bat")
        .unwrap_or_else(|| t.join("gradle-8.10.2"));
    let gradle_home = t.join("gradle-home");
    let sdk = t.join("android-sdk");
    let gradle_bat = gd.join("bin").join("gradle.bat");
    let adb = sdk.join("platform-tools").join("adb.exe");
    for p in [&jdk.join("bin").join("java.exe"), &gradle_bat, &sdk.join("platforms"), &gradle_home] {
        if !p.exists() {
            return Err(format!("工具链缺件：{}\n共享目录：{}（按 .tooling/env.sh 的约定补齐）", p.display(), t.display()));
        }
    }
    Ok(ToolChain { jdk, gradle_bin: gd.join("bin"), gradle_home, sdk, gradle_bat, adb })
}

fn has_tooling(c: &Path) -> bool {
    let t = c.join(".tooling");
    t.join("gradle-home").is_dir()
        && pick_sub(&t, "jdk-", "bin/java.exe").is_some()
        && pick_sub(&t, "gradle-", "bin/gradle.bat").is_some()
}

fn resolve_tooling_root(root: &Path, em: &Emitter) -> Result<PathBuf, String> {
    let mut cands: Vec<PathBuf> = vec![root.to_path_buf()];
    if let Ok(e) = env::var("ERATW_TOOLING_ROOT") {
        if !e.is_empty() { cands.push(PathBuf::from(e)); }
    }
    // git worktree 里没有 .tooling/（不入库）：从主工作树列表里找共享工具链。
    let cwd = win_slash(root);
    if let Ok(o) = Command::new("git").args(["-C", &cwd, "worktree", "list", "--porcelain"]).output() {
        if o.status.success() {
            for l in String::from_utf8_lossy(&o.stdout).lines() {
                if let Some(p) = l.trim().strip_prefix("worktree ") {
                    cands.push(PathBuf::from(p.trim()));
                }
            }
        }
    }
    if let Some(parent) = root.parent() {
        cands.push(parent.to_path_buf());
        if let Ok(rd) = fs::read_dir(parent) {
            for e in rd.flatten().take(80) {
                let p = e.path();
                if p.is_dir() { cands.push(p); }
            }
        }
    }
    cands.push(PathBuf::from(r"<work-root>\eratw-安卓移植计划"));
    for c in &cands {
        if has_tooling(c) {
            if c != root {
                em.line(&format!("代码根没有 .tooling/ ⇒ 工具链改用共享目录 {}（代码仍用 {}）", c.display(), root.display()));
            }
            return Ok(c.clone());
        }
    }
    Err(format!(
        "找不到共享工具链 .tooling/（找过 {} 个候选）。\n工作树里没有 .tooling 是正常的：设 ERATW_TOOLING_ROOT 指向主工作树，或把工具链放到代码根的 .tooling/。",
        cands.len()
    ))
}

fn find_sign_key(wf: &Wf) -> Option<PathBuf> {
    let cands = [
        wf.tooling_root.join("local.update-signing").join("k1-private.pk8.pem"),
        wf.root.join("local.update-signing").join("k1-private.pk8.pem"),
        wf.app.join("local.update-signing").join("k1-private.pk8.pem"),
    ];
    cands.iter().find(|p| p.is_file()).cloned()
}

fn notes_path(wf: &Wf) -> Result<PathBuf, String> {
    let v = wf.opts.notes_file.trim();
    if !v.is_empty() {
        let p = PathBuf::from(v);
        let cands = [p.clone(), wf.root.join(v), wf.app.join(v)];
        if let Some(q) = cands.iter().find(|q| q.is_file()) {
            return Ok(q.clone());
        }
        return Err(format!(
            "--notes-file 指定的发布说明不存在：{}\n（相对路径依次按 当前目录 / 代码根 / eratw-android 找过）",
            v
        ));
    }
    let p = wf.app.join("build").join(format!("release-notes-{}.md", wf.version_name));
    if p.is_file() { return Ok(p); }
    let note = if wf.opts.note.trim().is_empty() {
        format!("常规更新 {}", wf.version_name)
    } else {
        wf.opts.note.trim().to_string()
    };
    let size = fs::metadata(&wf.apk_path).map(|m| m.len()).unwrap_or(0);
    let body = format!(
        "## EraTW Pocket {v}（Android · 正式发行）\n\n{note}\n\n| 项 | 值 |\n| --- | --- |\n| 体积 | {sz} B |\n| SHA-256 | `{sha}` |\n| 升级 | 覆盖安装即可，请勿卸载（会清存档） |\n\n说明：崩溃 / 异常报告会自动上报；应用内自动更新通道保持开启。\n\n> 简版说明由工具生成；正式发行汇报请用 --notes-file 指定。\n",
        v = wf.version_name, note = note, sz = size, sha = wf.apk_sha
    );
    let _ = fs::create_dir_all(p.parent().unwrap_or(&wf.app));
    fs::write(&p, body).map_err(|e| format!("写 Release 说明失败：{}", e))?;
    Ok(p)
}

fn compute_sha256(wf: &Wf, apk: &Path) -> Result<String, String> {
    let code = "import hashlib,sys;h=hashlib.sha256();f=open(sys.argv[1],'rb');[h.update(b) for b in iter(lambda:f.read(1048576),b'')];print(h.hexdigest())";
    let o = Command::new(&wf.python).args(["-c", code, &win_slash(apk)]).output()
        .map_err(|e| format!("无法重算 sha256：{}", e))?;
    if !o.status.success() {
        return Err(format!("sha256 重算失败：{}", String::from_utf8_lossy(&o.stderr)));
    }
    String::from_utf8_lossy(&o.stdout).lines().last().unwrap_or("").trim().to_string()
        .parse::<String>().map_err(|e| e.to_string()).and_then(|v| {
            if v.len() == 64 && v.chars().all(|c| c.is_ascii_hexdigit()) { Ok(v) }
            else { Err(format!("sha256 形状不对：{}", v)) }
        })
}

fn ensure_sha256(wf: &Wf, apk: &Path) -> Result<String, String> {
    let computed = compute_sha256(wf, apk)?;
    let sha_file = PathBuf::from(format!("{}.sha256.txt", apk.display()));
    let cur = read_text(&sha_file).lines().next().unwrap_or("").trim().to_string();
    if cur.is_empty() {
        fs::write(&sha_file, format!("{}  {}\n", computed, apk.file_name().unwrap_or_default().to_string_lossy()))
            .map_err(|e| format!("补写 .sha256.txt 失败：{}", e))?;
        wf.em.line("归档缺 .sha256.txt，已按实算值补写");
        return Ok(computed);
    }
    if !cur.to_ascii_lowercase().starts_with(&computed.to_ascii_lowercase()) {
        return Err(format!(
            "归档 sha256 对账失败：.sha256.txt 写 {}，实际重算 {}。\n文件可能被替换/损坏，拒绝交付与发布。",
            cur, computed
        ));
    }
    Ok(computed)
}

fn apk_name_matches(wf: &Wf, apk: &Path) -> bool {
    let name = apk.file_name().unwrap_or_default().to_string_lossy().to_string();
    name.contains(&format!("{}+{}", wf.version_name, wf.version_code))
        || name.contains(&wf.version_name)
}

fn disk_free_bytes(wf: &Wf, p: &Path) -> Option<u64> {
    let code = "import shutil,sys;print(shutil.disk_usage(sys.argv[1]).free)";
    let o = Command::new(&wf.python).args(["-c", code, &win_slash(p)]).output().ok()?;
    if !o.status.success() { return None; }
    String::from_utf8_lossy(&o.stdout).lines().last()?.trim().parse::<u64>().ok()
}

fn online_manifest_text(_wf: &Wf) -> Option<String> {
    let mut c = Command::new("curl");
    c.args(["-sS", "--noproxy", "*", "--max-time", "20", "https://eratw-android.asia/updates/manifest.txt"])
        .stdout(Stdio::piped()).stderr(Stdio::piped());
    for k in PROXY_KEYS { c.env_remove(k); }
    let o = c.output().ok()?;
    if !o.status.success() { return None; }
    Some(String::from_utf8_lossy(&o.stdout).to_string())
}

fn manifest_field(t: &str, key: &str) -> Option<String> {
    for line in t.lines() {
        let l = line.trim();
        if let Some(rest) = l.strip_prefix(key) {
            let r = rest.trim();
            if !r.is_empty() { return Some(r.to_string()); }
        }
    }
    None
}

fn online_manifest_code(wf: &Wf) -> Option<i64> {
    let t = online_manifest_text(wf)?;
    manifest_field(&t, "apk-version-code")?.split_whitespace().next()?.parse::<i64>().ok()
}

fn target_version_code(wf: &Wf) -> i64 {
    if !wf.opts.bump_version.is_empty() && wf.opts.bump_version != wf.version_name {
        wf.version_code + 1
    } else {
        wf.version_code
    }
}

fn verify_online_manifest(wf: &mut Wf) {
    for i in 1..=3 {
        if let Some(t) = online_manifest_text(wf) {
            let code = manifest_field(&t, "apk-version-code").unwrap_or_default();
            let sha = manifest_field(&t, "apk-sha256").unwrap_or_default();
            let want = wf.version_code.to_string();
            if code.split_whitespace().next() == Some(want.as_str())
                && sha.to_ascii_lowercase().starts_with(&wf.apk_sha.to_ascii_lowercase()) {
                wf.online_ok = true;
                let short = &wf.apk_sha[..8.min(wf.apk_sha.len())];
                wf.em.line(&format!("线上清单复核通过：code {} / sha {}…", code, short));
                return;
            }
            let ssha = &sha[..12.min(sha.len())];
            wf.em.line(&format!("线上清单复核第 {} 次未对齐（code={} sha={}…），CDN 可能还在缓存", i, code, ssha));
        }
        thread::sleep(Duration::from_secs(4));
    }
    wf.em.line("⚠ 线上清单复核未通过：emit_update 自身上传对账已过，但工具复核读到旧值。请人工确认 manifest.txt（不阻断本次交付）。");
}

fn step_preflight(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    wf.em.stage(24, "发版前预检（把会白跑十分钟的闸门提前）");
    let mut checks: Vec<String> = Vec::new();

    // ① 磁盘：整包 729 MiB + 数据包 644 MiB + Gradle 中间产物，留 3 GiB。
    match disk_free_bytes(wf, &wf.app) {
        Some(b) if b < 3 * 1024 * 1024 * 1024 => {
            return Err(format!(
                "磁盘可用空间不足：{:.1} GiB（建议 ≥3 GiB）。\n位置：{}\n清掉旧归档 / Gradle 缓存再跑。",
                b as f64 / 1073741824.0, wf.app.display()
            ));
        }
        Some(b) => {
            let t = format!("磁盘可用 {:.1} GiB", b as f64 / 1073741824.0);
            wf.em.line(&t); checks.push(t);
        }
        None => wf.em.line("磁盘空间探测跳过（python 不可用，不影响出包）"),
    }

    let will_upload = wf.opts.mode == Mode::Release && wf.opts.upload
        && (!wf.opts.headless || wf.opts.confirm_upload);
    if will_upload {
        // ② 版本单调：线上 code 必须严格小于目标 code（emit_update 的硬闸）。
        let target = target_version_code(wf);
        match online_manifest_code(wf) {
            Some(online) if online >= target => {
                let msg = format!(
                    "线上 manifest 的 apk-version-code={} 已 ≥ 本地目标 {}：真发版会被单调闸拒发，请用 --version 抬新版本。",
                    online, target
                );
                if wf.opts.selftest {
                    wf.em.line(&format!("⚠ 预检提示（selftest 不因此判红）：{}", msg));
                } else {
                    return Err(msg);
                }
            }
            Some(online) => {
                let t = format!("线上 code {} < 目标 {}（单调闸通过）", online, target);
                wf.em.line(&t); checks.push(t);
            }
            None => wf.em.line("⚠ 线上 manifest 读不到（离线？），跳过单调预检；emit_update 仍会自己校验。"),
        }
        // ③ 清单签名私钥：以前是构建完才发现缺，白跑 8 分钟。
        if find_sign_key(wf).is_none() {
            return Err(format!(
                "找不到清单签名私钥 k1-private.pk8.pem。\n找过：{} / {} / {} 下的 local.update-signing/。\nemit_update 会拒发未签名清单；本工具刻意不提供 --unsigned-explicit 逃生门。",
                wf.tooling_root.display(), wf.root.display(), wf.app.display()
            ));
        }
        wf.em.line("清单签名私钥在位"); checks.push("清单签名私钥在位".into());
    }

    // ④ GitHub Release 前置条件（gh / 登录 / 仓库 / 正文），同样提前到构建前。
    if wf.opts.github_release && wf.opts.mode == Mode::Release {
        let mut c = Command::new("gh");
        c.arg("--version").stdout(Stdio::null()).stderr(Stdio::null());
        for k in PROXY_KEYS { c.env_remove(k); }
        if !c.status().map(|x| x.success()).unwrap_or(false) {
            return Err("找不到 gh CLI（发 GitHub Release 需要）。装好并 gh auth login，或去掉 --github-release。".into());
        }
        let mut c = Command::new("gh");
        c.args(["auth", "status"]);
        for k in PROXY_KEYS { c.env_remove(k); }
        if !c.output().map(|o| o.status.success()).unwrap_or(false) {
            return Err("gh 未登录或凭据失效：先跑 gh auth status / gh auth login，或去掉 --github-release。".into());
        }
        let mut c = Command::new("gh");
        c.args(["repo", "view", &wf.opts.github_repo]);
        for k in PROXY_KEYS { c.env_remove(k); }
        if !c.output().map(|o| o.status.success()).unwrap_or(false) {
            return Err(format!("看不到 GitHub 仓库 {}（不存在 / 无权限 / 网络不通）。用 --github-repo 改仓或去掉 --github-release。", wf.opts.github_repo));
        }
        if !wf.opts.selftest {
            let p = notes_path(wf)?;
            let t = format!("Release 正文 = {}", p.display());
            wf.em.line(&t); checks.push(t);
        } else if !wf.opts.notes_file.is_empty() {
            // selftest 不该产生副作用：只验证显式指定的正文存在，不生成简版。
            let v = wf.opts.notes_file.trim();
            let p = PathBuf::from(v);
            if ![p.clone(), wf.root.join(v), wf.app.join(v)].iter().any(|q| q.is_file()) {
                return Err(format!("--notes-file 指定的发布说明不存在：{}", v));
            }
            let t = format!("Release 正文（仅校验）= {}", p.display());
            wf.em.line(&t); checks.push(t);
        }
        checks.push("gh CLI / 登录 / 仓库可见".into());
    }

    // ⑤ 装机设备（只提示，不阻断：没设备时 APK 照样能拷走）。
    if wf.opts.install {
        if !wf.adb.is_file() {
            wf.em.line("⚠ 找不到 adb.exe，出完将跳过装机");
        } else {
            let mut c = Command::new(&wf.adb);
            c.args(["devices"]);
            if let Ok(o) = c.output() {
                let t = String::from_utf8_lossy(&o.stdout);
                let devs: Vec<&str> = t.lines().filter(|l| l.contains("\tdevice")).collect();
                if devs.is_empty() {
                    wf.em.line("⚠ 没有已授权设备，出完将跳过装机");
                } else if devs.len() > 1 && wf.opts.device.is_empty() {
                    wf.em.line(&format!("⚠ 检测到 {} 台设备且未用 --device 指定，出完将跳过装机（避免装错机器）", devs.len()));
                } else {
                    let t = format!("装机目标设备 = {}", wf.opts.device.as_str());
                    if !wf.opts.device.is_empty() { wf.em.line(&t); }
                }
            }
        }
    }

    if wf.opts.mode == Mode::Dev && wf.opts.upload {
        wf.em.line("ℹ 测试包永远不上传（--no-update 语义），忽略 upload 勾选。");
    }
    wf.checks = checks;
    Ok(())
}

// ------------------------------------------------------------- 流程各步骤

fn resolve_root(opts: &Opts) -> Result<PathBuf, String> {
    // --root 是「明确的意图」：给错了必须报错，绝不能静默退回主树去打错包。
    if !opts.root_override.is_empty() {
        let c = PathBuf::from(&opts.root_override);
        if !has_code_marker(&c) {
            return Err(format!(
                "--root 指定的目录不是合法代码根：{}\n要求含 eratw-android/devtools/pack_release.py。工具拒绝静默改用别的树，避免打错包。",
                c.display()
            ));
        }
        return Ok(c);
    }
    let mut cands: Vec<PathBuf> = Vec::new();
    if let Ok(e) = env::var("ERATW_ROOT") {
        if !e.is_empty() { cands.push(PathBuf::from(e)); }
    }
    if let Ok(exe) = env::current_exe() {
        let mut p = exe.parent().map(|x| x.to_path_buf());
        while let Some(d) = p {
            cands.push(d.clone());
            p = d.parent().map(|x| x.to_path_buf());
        }
    }
    cands.push(PathBuf::from(r"<work-root>\eratw-安卓移植计划"));
    for c in &cands {
        if has_code_marker(c) { return Ok(c.clone()); }
    }
    Err(format!(
        "找不到代码根（需含 eratw-android/devtools/pack_release.py）。\n已试过 {} 个候选；把 exe 放进仓库内，或用 --root <路径> / ERATW_ROOT 指定。",
        cands.len()
    ))
}

fn detect_python(wf: &Wf) -> Result<String, String> {
    for cand in ["python", "py"] {
        let args: Vec<String> = if cand == "py" { sargs(&["-3", "--version"]) } else { sargs(&["--version"]) };
        let mut c = Command::new(cand);
        c.args(&args).stdout(Stdio::piped()).stderr(Stdio::piped());
        for k in PROXY_KEYS {
            c.env_remove(k);
        }
        if let Ok(o) = c.output() {
            if o.status.success() {
                wf.em.line(&format!("python = {} ({})", cand, String::from_utf8_lossy(&o.stdout).trim()));
                return Ok(if cand == "py" { "py".into() } else { "python".into() });
            }
        }
    }
    Err("找不到 python（试过 python / py -3）。请安装 Python 3 并加入 PATH。".into())
}

fn py_args(wf: &Wf, script: &str, extra: &[String]) -> Vec<String> {
    let mut a = Vec::new();
    if wf.python == "py" {
        a.push("-3".to_string());
    }
    a.push(script.to_string());
    a.extend(extra.iter().cloned());
    a
}

fn parse_version_parts(app: &Path) -> (String, i64, String) {
    let t = read_text(&app.join("app").join("build.gradle.kts"));
    let mut code: i64 = 0;
    let mut name = String::new();
    for line in t.lines() {
        let l = line.trim();
        if l.starts_with("versionCode") {
            if let Some(v) = l.split('=').nth(1) {
                if let Some(tok) = v.trim().split_whitespace().next() {
                    if let Ok(n) = tok.trim_end_matches(',', ).parse::<i64>() { code = n; }
                }
            }
        } else if l.starts_with("versionName") {
            if let Some(v) = l.split('=').nth(1) {
                let tok = v.trim().trim_matches('"').split_whitespace().next().unwrap_or("").to_string();
                if !tok.is_empty() { name = tok; }
            }
        }
    }
    let display = format!("{}+{}", name, code);
    (name, code, display)
}

fn parse_version(app: &Path) -> String { parse_version_parts(app).2 }

/// 现行语料根目录名 —— **单源取用**（CONTRACT-ENGCPL E7）。
///
/// 唯一出处是手写 `specs/engine-profiles.input.yaml` → 生成物
/// `specs/engine-profiles.yaml` 的 `corpora:` 段。本工具不再刮 `pack_release.py` 的源码
/// 文本、也不再有内置字面兜底：直接问出包脚本要（`--print-corpus-root-name`，它在任何
/// 构建/写盘之前早退）。取不到 / 输出不是目录名一律 `Err` —— 一份错语料根会静默出成
/// 「错数据 + 对版本号」的包，体检全绿且无一处拦得住，所以这里不许有任何兜底。
fn corpus_dir_name(wf: &Wf) -> Result<String, String> {
    let script = wf.app.join("devtools").join("pack_release.py");
    if !script.is_file() {
        return Err(format!(
            "找不到出包脚本 {}：语料根名（--print-corpus-root-name）是唯一取值通道，拒绝猜一个目录名",
            script.display()
        ));
    }
    let extra = ["--print-corpus-root-name".to_string()];
    let args = py_args(wf, &win_slash(&script), &extra);
    let (code, out) = execute(wf, &wf.python, &args, &wf.app)?;
    if code != 0 {
        return Err(format!(
            "--print-corpus-root-name 退出码 {}（语料根名单源读失败；拒绝回落到字面量）：\n{}",
            code,
            tail(&out, 20)
        ));
    }
    let name = out
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .last()
        .unwrap_or("")
        .to_string();
    if name.is_empty() {
        return Err("--print-corpus-root-name 没有输出任何目录名（空输出 = 语料根名单源链路坏了）".into());
    }
    if name.contains('/') || name.contains('\\') || name.contains("..") || name.contains(':') {
        return Err(format!(
            "--print-corpus-root-name 输出 '{}' 不是目录名（含路径分隔符 / .. / 盘符），拒绝当成语料根",
            name
        ));
    }
    Ok(name)
}

fn step_bump(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    let v = wf.opts.bump_version.trim().to_string();
    if v.is_empty() {
        wf.em.line("版本号保持不动（未指定 --version）");
        return Ok(());
    }
    let parts: Vec<&str> = v.split('.').collect();
    if parts.len() < 2 || parts.len() > 4
        || parts.iter().any(|p| p.is_empty() || !p.chars().all(|c| c.is_ascii_digit())) {
        return Err(format!("版本号形状不对：{}（应形如 0.2.45，只允许数字与点）", v));
    }
    if wf.opts.mode != Mode::Release {
        wf.em.line(&format!("⚠ 包型是「{}」却指定了 --version {}：只改本地工作树、不发版。", wf.opts.mode.short(), v));
    }
    let old_name = wf.version_name.clone();
    let old_code = wf.version_code;
    if v == old_name {
        wf.version = format!("{}+{}", v, old_code);
        wf.em.line(&format!("版本号已是 {} +{}，不重复抬号（重试安全）", v, old_code));
        return Ok(());
    }
    if cmp_version(&v, &old_name) != std::cmp::Ordering::Greater {
        return Err(format!(
            "版本号不能回退/改号持平：{} → {}。发版要求 versionName 与 versionCode 同时单调递增。",
            old_name, v
        ));
    }
    let new_code = old_code + 1;
    let p = wf.app.join("app").join("build.gradle.kts");
    let t = read_text(&p);
    if t.is_empty() { return Err(format!("读不到 {}", p.display())); }
    let stamp = now_stamp();
    let date = format!("{}-{}-{}", &stamp[0..4], &stamp[4..6], &stamp[6..8]);
    let mut out = String::new();
    let mut seen_code = false;
    let mut seen_name = false;
    for line in t.lines() {
        let l = line.trim_start();
        let indent: String = line.chars().take_while(|c| c.is_whitespace()).collect();
        if l.starts_with("versionCode =") && !seen_code {
            seen_code = true;
            out.push_str(&format!("{}// {} / {}（{}）：一键出包工具按发版要求自动抬号。\n", indent, v, new_code, date));
            out.push_str(&format!("{}versionCode = {}\n", indent, new_code));
        } else if l.starts_with("versionName =") && !seen_name {
            seen_name = true;
            out.push_str(&format!("{}versionName = \"{}\"\n", indent, v));
        } else {
            out.push_str(line);
            out.push('\n');
        }
    }
    if !seen_code || !seen_name {
        return Err(format!("{} 里没同时找到 versionCode / versionName，拒绝盲改版本号。", p.display()));
    }
    let backup = wf.app.join("build").join(format!("build.gradle.kts.bak-{}", stamp));
    let _ = fs::create_dir_all(backup.parent().unwrap_or(&wf.app));
    let _ = fs::write(&backup, t.as_bytes());
    fs::write(&p, out.as_bytes()).map_err(|e| format!("写回 {} 失败：{}", p.display(), e))?;
    wf.version_name = v.clone();
    wf.version_code = new_code;
    wf.version = format!("{}+{}", v, new_code);
    wf.em.line(&format!("抬号：{} +{} → {} +{}（已写入 app/build.gradle.kts；原件备份 {}）", old_name, old_code, v, new_code, backup.display()));
    wf.em.line("注意：抬号未提交，归档名会带 -dirty；代码本体与 HEAD 一致。");
    Ok(())
}

fn step_toolchain(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    wf.em.stage(3, "检查仓库与工具链");
    let need = [
        wf.app.join("devtools").join("pack_release.py"),
        wf.app.join("devtools").join("emit_update.py"),
        wf.app.join("devtools").join("erbc-degraded-allowlist.tsv"),
        wf.app.join("gradlew.bat"),
        wf.gradle_bat.clone(),
        wf.gradle_home.clone(),
        wf.jdk_dir.join("bin").join("java.exe"),
        wf.sdk_dir.join("platforms"),
    ];
    for p in &need {
        if !p.exists() {
            return Err(format!("缺文件/目录：{}\n→ 共享工具链根 {} 不完整，按 .tooling/env.sh 的约定补齐 JDK / Gradle / Android SDK。", p.display(), wf.tooling_root.display()));
        }
    }
    wf.python = detect_python(wf)?;
    let (vn, vc, _) = parse_version_parts(&wf.app);
    wf.version_name = vn;
    wf.version_code = vc;
    wf.version = format!("{}+{}", wf.version_name, wf.version_code);
    wf.em.line(&format!("代码根   = {}", wf.root.display()));
    wf.em.line(&format!("工具链根 = {}（{} / {}）", wf.tooling_root.display(),
        wf.jdk_dir.file_name().unwrap_or_default().to_string_lossy(),
        wf.gradle_bat.display()));
    wf.em.line(&format!("当前版本 = {} · 包型 = {}", wf.version, wf.opts.mode.short()));
    Ok(())
}

fn step_git(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    if !wf.opts.sync_git {
        wf.em.line("跳过 git 同步（按勾选）");
    } else {
        wf.em.stage(8, "同步远程代码");
        let (_, o) = execute(wf, "git", &sargs(&["symbolic-ref", "-q", "refs/remotes/origin/HEAD"]), &wf.root)?;
        let mut upstream = o.lines().map(|l| l.trim()).find(|l| l.starts_with("refs/")).unwrap_or("").to_string();
        if upstream.is_empty() { upstream = "origin/main".to_string(); }
        wf.em.line(&format!("上游 = {}", upstream));
        let fetch = sargs(&["fetch", "origin", "--prune"]);
        let mut ok = false;
        // 直连优先（沙箱注入的代理会掩盖真实可达性）；直连不通再带代理兜一次。
        for i in 1..=2 {
            let (code, out) = execute(wf, "git", &fetch, &wf.root)?;
            if code == 0 { ok = true; break; }
            wf.em.line(&format!("git fetch 直连第 {} 次失败：{}", i, tail(&out, 1)));
        }
        if !ok {
            wf.em.line("直连不通，带系统代理兜一次…");
            let (code, out) = execute_ex(wf, "git", &fetch, &wf.root, false)?;
            ok = code == 0;
            if !ok { wf.em.line(&format!("代理重试也失败：{}", tail(&out, 1))); }
        }
        if !ok {
            wf.em.line("⚠ 拉取失败（离线？），沿用本地 HEAD；归档 git7 仍是本地提交。");
        } else {
            // 修 bug：rev-list 输出是「ahead\tbehind」同一行两个数，旧版只取了每行第一个数，
            // 导致 behind 永远是 0、永远不快进。
            let spec = format!("HEAD...{}", upstream);
            let (rc, c) = execute(wf, "git", &sargs(&["rev-list", "--left-right", "--count", &spec]), &wf.root)?;
            if rc == 0 {
                let nums: Vec<i64> = c.lines()
                    .flat_map(|l| l.split_whitespace().filter_map(|x| x.parse::<i64>().ok()))
                    .collect();
                let ahead = nums.first().copied().unwrap_or(0);
                let behind = nums.get(1).copied().unwrap_or(0);
                if ahead > 0 && behind > 0 {
                    wf.em.line(&format!("⚠ 本地与上游分叉（ahead {} / behind {}），拒绝快进，保留你的改动。", ahead, behind));
                } else if ahead == 0 && behind > 0 {
                    let (_, st) = execute(wf, "git", &sargs(&["status", "--porcelain", "--untracked-files=no"]), &wf.root)?;
                    let dirty = st.lines().any(|l| !l.trim().is_empty());
                    if dirty {
                        wf.em.line(&format!("⚠ 有 {} 个提交可快进，但工作树有已跟踪改动，跳过快进（不覆盖你的修改）。", behind));
                    } else {
                        let (code, out) = execute(wf, "git", &sargs(&["merge", "--ff-only", &upstream]), &wf.root)?;
                        if code == 0 {
                            wf.code_changed = true;
                            wf.em.line(&format!("已快进 {} 个提交到 {}（erbc 是否重编改由 compiler-digest 内容对账决定）", behind, upstream));
                        } else {
                            wf.em.line(&format!("⚠ 快进失败，沿用本地 HEAD：{}", tail(&out, 3)));
                        }
                    }
                } else if ahead > 0 {
                    wf.em.line(&format!("本地领先 {} 个提交，不做快进", ahead));
                } else {
                    wf.em.line("本地已是最新");
                }
            } else {
                wf.em.line("⚠ 无法比较与上游的距离（无上游/离线？），沿用本地 HEAD");
            }
        }
    }
    let (_, h) = execute(wf, "git", &sargs(&["rev-parse", "--short=7", "HEAD"]), &wf.root)?;
    wf.git7 = h.lines().rev().map(|l| l.trim())
        .find(|l| l.len() >= 7 && l.chars().take(7).all(|c| c.is_ascii_hexdigit()))
        .unwrap_or("unknown").to_string();
    let (vn, vc, _) = parse_version_parts(&wf.app);
    wf.version_name = vn;
    wf.version_code = vc;
    wf.version = format!("{}+{}", wf.version_name, wf.version_code);
    wf.em.line(&format!("HEAD = {} · 版本 {}（抬号前）", wf.git7, wf.version));
    Ok(())
}

fn step_corpus(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    wf.em.stage(14, "校准语料根（正式包必须 4.981）");
    let want = corpus_dir_name(wf)?;
    let mut cands: Vec<PathBuf> = Vec::new();
    let bases = [wf.root.clone(), wf.tooling_root.clone()];
    for base in &bases {
        cands.push(base.join(&want));
        if let Ok(rd) = fs::read_dir(base) {
            for e in rd.flatten().take(120) {
                let p = e.path();
                if p.is_dir() && p.file_name().unwrap_or_default().to_string_lossy().contains("4.981") {
                    cands.push(p.clone());
                }
            }
        }
    }
    // 工作树里没有语料是常态：去父目录 / 兄弟目录（主工作树）找。
    for base in &bases {
        if let Some(parent) = base.parent() {
            cands.push(parent.join(&want));
            if let Ok(rd) = fs::read_dir(parent) {
                for e in rd.flatten().take(120) {
                    let p = e.path();
                    if p.is_dir() {
                        cands.push(p.join(&want));
                        if p.file_name().unwrap_or_default().to_string_lossy().contains("4.981") {
                            cands.push(p.clone());
                        }
                    }
                }
            }
        }
    }
    // 显式环境变量最后才考虑：env.sh 的缺省值可能仍指已退役的 4.980。
    if let Ok(e) = env::var("ERATW_GAMEDIR") {
        if !e.is_empty() { cands.push(PathBuf::from(e)); }
    }
    let chosen = cands.iter().find(|p| p.join("ERB").is_dir()).cloned().ok_or_else(|| {
        format!("找不到 4.981 语料根（脚本要求 {}，且需含 ERB/ 子目录）。\n找过 {} 个候选；工作树里没有语料是正常的，工具会自动去主工作树找。", want, cands.len())
    })?;
    let actual = chosen.file_name().unwrap_or_default().to_string_lossy().to_string();
    if actual != want {
        wf.em.line(&format!("⚠ 语料目录名 {} 与脚本常量 {} 不一致：正式包可能被 4.981 硬闸拒绝。", actual, want));
    }
    wf.game_root = chosen.clone();
    wf.em.line(&format!("ERATW_GAMEDIR = {}", chosen.display()));
    wf.em.line(&format!("ERATW_CORPUS  = {}", chosen.join("ERB").display()));
    Ok(())
}

fn ensure_props(wf: &Wf, name: &str, keys: &[&str], required: bool) -> Result<bool, String> {
    let dst = wf.app.join(name);
    if has_keys(&dst, keys) {
        wf.em.line(&format!("配置 OK：{}（{}）", dst.display(), keys.join("/")));
        return Ok(true);
    }
    wf.em.line(&format!("修配置：{} 缺 {}，全盘找一份补齐…", dst.display(), keys.join("/")));
    let src = find_props_source(&wf.root, name, keys)
        .or_else(|| if wf.tooling_root != wf.root { find_props_source(&wf.tooling_root, name, keys) } else { None });
    if let Some(src) = src {
        if let Some(parent) = dst.parent() { let _ = fs::create_dir_all(parent); }
        fs::copy(&src, &dst).map_err(|e| format!("复制 {} → {} 失败：{}", src.display(), dst.display(), e))?;
        wf.em.line(&format!("已从 {} 补齐（内容不回显）", src.display()));
        return Ok(true);
    }
    if required {
        Err(format!(
            "找不到合格的 {}（需要非空非占位 {}）。\n脚本只认 {} 这一处——仓库根那一份不算。\n请向部署者索取，或放一份到代码根后重跑。",
            name, keys.join(" / "), dst.display()
        ))
    } else {
        wf.em.line(&format!("⚠ 没找到 {}，本次不依赖它，继续。", name));
        Ok(false)
    }
}

fn step_config(wf: &mut Wf) -> Result<(), String> {
    check_cancel()?;
    wf.em.stage(20, "补齐端点/令牌/签名章配置");
    ensure_props(wf, "local.report.properties", &["endpoint", "token"], true)?;
    let m = parse_props(&wf.app.join("local.report.properties"));
    let endpoint = m.get("endpoint").cloned().unwrap_or_default();
    let token = m.get("token").cloned().unwrap_or_default();
    if !endpoint.starts_with("https://") || token.len() < 8 || placeholder(&token) {
        return Err(format!(
            "local.report.properties 不合格：endpoint 必须 https，token 必须非空非占位。\n文件：{}",
            wf.app.join("local.report.properties").display()
        ));
    }
    wf.em.line(&format!("上报端点 = {}（令牌 {} 位，不回显）", endpoint, token.len()));
    let gp = wf.gradle_home.join("gradle.properties");
    if !has_keys(&gp, SIGN_KEYS) {
        return Err(format!(
            "发布签名章没配齐（{}）。\n文件：{}\n缺了会静默签成 debug keystore，玩家只能卸载重装且丢存档。",
            SIGN_KEYS.join(" / "), gp.display()
        ));
    }
    let sm = parse_props(&gp);
    if let Some(sf) = sm.get("eratwReleaseStoreFile") {
        let kp = PathBuf::from(sf.replace('\\', "/"));
        let resolved = if kp.is_absolute() { kp } else { wf.gradle_home.join(kp) };
        if !resolved.is_file() {
            wf.em.line(&format!("⚠ 签名章指向的 keystore 按当前解析不存在：{}（若 Gradle 用别的基准路径，可忽略）", resolved.display()));
        }
    }
    wf.em.line("发布签名章四键齐备");
    if wf.opts.mode == Mode::Release && wf.opts.upload {
        // selftest 也要查：R2 缺键是发版硬闸，不能等构建完才发现（只读补齐，无副作用风险）。
        ensure_props(wf, "local.r2.properties", R2_KEYS, true)?;
        wf.em.line("R2 桶级凭据齐备（正式包上传需要）");
    }
    Ok(())
}

// 判据一（权威）：gradle --status 的 STATUS 列。只有 BUSY 才是真有构建在跑，
// IDLE/STOPPED 的 daemon 仍会独占 caches/*.lock，这时必须 --stop 释放，不能干等。
// 日志判据曾把「空闲 daemon 每 10 秒的心跳 + 尾部残留的历史 Starting build」
// 当成活跃构建，白等 90 秒后直接失败（2026-11-12 工作树实跑踩到）。
fn gradle_status_text(wf: &Wf) -> Option<String> {
    let g = win_slash(&wf.gradle_bat);
    let mut c = Command::new("cmd");
    c.args(&sargs(&["/c", &g, "--status"]))
        .current_dir(&wf.app)
        .stdout(Stdio::piped()).stderr(Stdio::piped());
    for (k, v) in wf.env() { c.env(k, v); }
    for k in PROXY_KEYS { c.env_remove(k); }
    let mut child = c.spawn().ok()?;
    // --status 正常 1~3 秒；不给它套 45 分钟的构建看门狗，25 秒拿不到就回退日志判据。
    let deadline = Instant::now() + Duration::from_secs(25);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() >= deadline => { let _ = child.kill(); return None; }
            Ok(None) => thread::sleep(Duration::from_millis(200)),
            Err(_) => return None,
        }
    }
    let out = child.wait_with_output().ok()?;
    let mut s = String::from_utf8_lossy(&out.stdout).to_string();
    s.push_str(&String::from_utf8_lossy(&out.stderr));
    Some(s)
}

// None = --status 不可用（回退日志判据）；Some(vec![]) = 无 BUSY；Some(pids) = 真有构建。
fn busy_daemon_pids(wf: &Wf) -> Option<Vec<String>> {
    let text = gradle_status_text(wf)?;
    let mut seen_status_row = false;
    let mut has_table = false;
    let mut busy = Vec::new();
    for l in text.lines() {
        // 表头 "PID STATUS INFO" 出现即说明 --status 正常返回；即使一行 daemon 都没有
        // （全部已退出），也应当判定「无 BUSY」，不能回退到会误判的历史日志判据。
        if l.split_whitespace().any(|w| w.eq_ignore_ascii_case("STATUS")) { has_table = true; }
        let f: Vec<&str> = l.split_whitespace().collect();
        // 状态行形如 " 28160 IDLE 8.10.2"：第一列纯数字 PID，第二列 STATUS。
        if f.len() >= 2 && !f[0].is_empty() && f[0].chars().all(|c| c.is_ascii_digit()) {
            seen_status_row = true;
            if f[1].eq_ignore_ascii_case("BUSY") { busy.push(f[0].to_string()); }
        }
    }
    if seen_status_row || has_table { Some(busy) } else { None }
}

// 回退判据：只在 --status 拿不到时用，且要求 Starting build 行的时间戳确实是最近
// 10 分钟内（历史日志里残留的 START 行不能再被当成「现在有人在跑」）。
fn daemon_busy_from_logs(root: &Path) -> bool {
    let dd = root.join(".tooling").join("gradle-home").join("daemon");
    let cutoff = SystemTime::now() - Duration::from_secs(180);
    let now14 = now_stamp().replace('-', "");
    let floor14 = {
        // 本地 10 分钟前，用秒数回推再格式化，避免手写日期借位。
        let secs = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_secs() - 600;
        let (y, mo, d, h, mi, s) = civil_from_epoch(secs);
        format!("{:04}{:02}{:02}{:02}{:02}{:02}", y, mo, d, h, mi, s)
    };
    let mut stack = vec![dd];
    while let Some(d) = stack.pop() {
        if let Ok(rd) = fs::read_dir(&d) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() { stack.push(p); continue; }
                if p.extension().map(|x| x == "log").unwrap_or(false) {
                    let md = fs::metadata(&p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH);
                    if md <= cutoff { continue; }
                    let t = read_text(&p);
                    let lines: Vec<&str> = t.lines().collect();
                    for l in lines.iter().rev().take(600) {
                        if l.contains("BUILD SUCCESSFUL") || l.contains("BUILD FAILED")
                            || l.contains("Build cancelled") || l.contains("Build aborted") {
                            break;
                        }
                        if l.contains("Starting build") || l.contains("Executing build") {
                            // 行首形如 2026-11-12T01:42:04.937+0800；太旧的一律不算活跃。
                            if l.len() >= 19 {
                                let t14: String = l[..19].chars()
                                    .filter(|c| c.is_ascii_digit()).collect();
                                if t14.len() >= 14 && t14[..14] >= floor14[..] && t14[..14] <= now14[..] {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    false
}

fn daemon_busy(wf: &Wf) -> (bool, String) {
    if let Some(pids) = busy_daemon_pids(wf) {
        return if pids.is_empty() {
            (false, "gradle --status：无 BUSY daemon（IDLE/STOPPED 持锁可直接释放）".into())
        } else {
            (true, format!("gradle --status：BUSY daemon PID {} 正在构建", pids.join(", ")))
        };
    }
    let busy = daemon_busy_from_logs(&wf.tooling_root);
    (busy, if busy { "gradle --status 不可用，日志判据显示近期有构建".into() }
           else { "gradle --status 不可用，日志判据未发现近期构建".into() })
}

// Gradle 不只占 journal-1.lock：caches/8.10.2/fileHashes|fileContent|generated-gradle-jars
// 下三个 .lock 被空闲 daemon 独占时，pack_release 会在启动阶段直接
// "Could not create service ... ValueSnapshotter / fileHashes.lock 拒绝访问" 挂掉。全扫一遍。
fn probe_lock_dir(d: &Path, out: &mut Vec<PathBuf>) {
    if let Ok(rd) = fs::read_dir(d) {
        for e in rd.flatten() {
            let p = e.path();
            if p.is_file() && p.extension().map(|x| x == "lock").unwrap_or(false)
                && OpenOptions::new().read(true).write(true).open(&p).is_err() {
                out.push(p);
            }
        }
    }
}

/// 缓存锁的实际落点只有三层（2026-11-14 实测共 16 个）：
///   `caches/<dir>/*.lock`（journal-1 / build-cache-1）、
///   `caches/8.10.2/<dir>/*.lock`（fileHashes / fileContent / generated-gradle-jars …）。
///
/// 旧实现递归遍历整棵 `caches`（含 build-cache-1 下几十万条缓存条目），实测单次 83 秒；
/// 一次出包在 26% 和 40% 各扫一遍，近 3 分钟纯 I/O 浪费。这里限定三层并跳过已知大目录的
/// 子树，覆盖全部实际锁位置；Gradle 升级后若出现启动期新锁报错，先按报错路径补进本函数。
fn locked_files(root: &Path) -> Vec<PathBuf> {
    let base = root.join(".tooling").join("gradle-home").join("caches");
    let mut out: Vec<PathBuf> = Vec::new();
    if !base.is_dir() { return out; }
    probe_lock_dir(&base, &mut out);
    let skip = |n: &str| matches!(n, "modules-2" | "transforms-3" | "jars-9"
        | "transform-cache" | "8.10.2.bak");
    if let Ok(l1) = fs::read_dir(&base) {
        for e1 in l1.flatten() {
            let d1 = e1.path();
            if !d1.is_dir() { continue; }
            let n1 = d1.file_name().unwrap_or_default().to_string_lossy().to_string();
            if skip(&n1) { continue; }
            probe_lock_dir(&d1, &mut out);
            // build-cache-1 下面是逐条缓存产物，条目数与构建次数同量级，绝不递归进去；
            // 它自己的 build-cache-1.lock 已在上面 probe 过。
            if n1 == "build-cache-1" { continue; }
            if let Ok(l2) = fs::read_dir(&d1) {
                for e2 in l2.flatten() {
                    let d2 = e2.path();
                    if d2.is_dir() { probe_lock_dir(&d2, &mut out); }
                }
            }
        }
    }
    out
}

fn lock_writable(root: &Path) -> bool { locked_files(root).is_empty() }

fn release_locks(wf: &mut Wf, pct: usize, label: &str) -> Result<(), String> {
    check_cancel()?;
    wf.em.stage(pct, label);
    let locked = locked_files(&wf.tooling_root);
    if locked.is_empty() {
        wf.em.line("Gradle 缓存锁全部可写，继续");
        return Ok(());
    }
    wf.em.line(&format!("有 {} 个缓存锁被独占：", locked.len()));
    for p in locked.iter().take(8) { wf.em.line(&format!("  · {}", p.display())); }
    let (mut busy, why) = daemon_busy(wf);
    wf.em.line(&why);
    if busy {
        // 真有别人的构建：宽限 90 秒，每轮重新问 --status（不能只凭第一次判定死等），
        // 构建一结束就立刻停掉空闲 daemon 释放锁，绝不 kill 别人的构建。
        wf.em.line("检测到活跃构建，最多等 90 秒（每 10 秒复查一次）…");
        for i in 1..=9 {
            thread::sleep(Duration::from_secs(10));
            check_cancel()?;
            if lock_writable(&wf.tooling_root) {
                wf.em.line(&format!("锁在第 {} 次检查时释放（并行构建自行结束）", i));
                return Ok(());
            }
            let (now_busy, now_why) = daemon_busy(wf);
            if !now_busy {
                wf.em.line(&format!("第 {} 次复查：{} → 构建已结束，转去释放空闲 daemon", i, now_why));
                busy = false;
                break;
            }
        }
        if busy {
            return Err("有另一条 Gradle 构建仍在跑（已等 90 秒，gradle --status 仍为 BUSY）。\n本工具不会停掉别人的构建：等它结束再点【开始】。".into());
        }
    } else {
        wf.em.line("没有活跃构建（锁由空闲 daemon 持有）→ 停 daemon 释放锁");
    }
    let g = win_slash(&wf.gradle_bat);
    let _ = execute(wf, "cmd", &sargs(&["/c", &g, "--stop"]), &wf.app);
    thread::sleep(Duration::from_secs(2));
    if lock_writable(&wf.tooling_root) {
        wf.em.line("锁已全部释放");
        Ok(())
    } else {
        // 二次尝试：--stop 有时要等 daemon 完全退出才交还文件句柄。
        for _ in 0..5 {
            thread::sleep(Duration::from_secs(2));
            if lock_writable(&wf.tooling_root) {
                wf.em.line("锁在 daemon 退出后释放");
                return Ok(());
            }
        }
        let left = locked_files(&wf.tooling_root);
        Err(format!("停 daemon 后仍有 {} 个锁被占用（如 {}）。请检查是否有其它进程在用共享 .tooling/gradle-home，关闭后重跑。",
            left.len(), left.first().map(|p| p.display().to_string()).unwrap_or_default()))
    }
}

fn step_lock(wf: &mut Wf) -> Result<(), String> {
    release_locks(wf, 26, "检查 Gradle 缓存锁")
}

/// 降级清单在册条数（# 注释与空行不算）。0 = 第三方口上已在上游修干净，
/// 本轮走 strict 档、出包不带 --allow-degraded-corpus；>0 才回 compat + 容错清单。
/// 清单读不到按 0 处理：出包期 manifest 的 degraded-slices 闸会 fail-closed 兜底。
fn degraded_allowlist_count(wf: &Wf) -> usize {
    let p = wf.app.join("devtools").join("erbc-degraded-allowlist.tsv");
    read_text(&p).lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .count()
}

/// erbc 新鲜度：与 `ErbcFreshness.kt` / `ErbcCompilerDigest.kt` 同口径的 compiler-digest 对账，
/// 外加 `devtools/corpus-patches` 资产指纹（2026-11-20 事故：只改语料补丁的提交两个旧判据都看不见，
/// 复用了旧 erbc，玩家侧修复等于没发）。
/// 算法单源在 `devtools/erbc_freshness_probe.py`（两棵 .kt 树 + 补丁目录的内容 SHA-256）。
///
/// Ok(true)  = 编译器源码没变，现成 program.erbc 可复用（省掉一次 6 分钟的 compileCorpus）；
/// Ok(false) = 编译器源码变过，必须重编，否则出包期 CMP-66 硬闸必然拒包；
/// Err(..)   = 探针不可用，由 step_erbc 退回旧 mtime 判据（fail-safe，宁可多重编）。
fn erbc_digest_probe(wf: &Wf) -> Result<bool, String> {
    let probe = wf.app.join("devtools").join("erbc_freshness_probe.py");
    if !probe.is_file() {
        return Err(format!("找不到新鲜度探针 {}", probe.display()));
    }
    let o = Command::new(&wf.python)
        .arg(&probe)
        .arg("--short")
        .current_dir(&wf.app)
        .env("PYTHONUTF8", "1")
        .env("PYTHONIOENCODING", "utf-8")
        .output()
        .map_err(|e| format!("新鲜度探针启动失败：{}", e))?;
    let out = String::from_utf8_lossy(&o.stdout);
    let line = out.lines().last().unwrap_or("").trim();
    if line.starts_with("MATCH ") {
        Ok(true)
    } else if line.starts_with("MISMATCH ") {
        Ok(false)
    } else {
        Err(format!("新鲜度探针无结论（退出码 {:?}）：{} {}",
            o.status.code(), line, String::from_utf8_lossy(&o.stderr).trim()))
    }
}

/// 返回 true = 本轮真的跑了 compileCorpus（调用方据此决定要不要再查一次缓存锁）。
fn step_erbc(wf: &mut Wf, force: bool) -> Result<bool, String> {
    check_cancel()?;
    wf.em.stage(32, "检查/编译随包字节码 erbc.v1");
    let existing = find_program_erbc(&wf.app);
    let mut fresh = false;
    if let Some(ref p) = existing {
        if !force && !wf.opts.force_erbc {
            let pm = fs::metadata(p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH);
            // 语料根是未跟踪目录（git 不会刷它的 mtime），继续用 mtime 快速判；
            // 出包期 stageGameAssets 另有逐文件 sha 硬闸兜底，不会靠这里放行病灶语料。
            let corpus = newest_mtime_below(&wf.game_root.join("ERB"), 6);
            let corpus_ok = pm >= corpus;
            // 旧判据用「三棵源码树的 mtime + 本次拉过代码就强制重编」，每天 git 快进后
            // 哪怕只改文档也要白跑 6m23s 的 compileCorpus，且多扫的 buildSrc 根本不在
            // compiler-digest 口径里。erbc 是「编译器源码 + 语料」的纯函数，与 app 代码、
            // BOARD 文档、提交数都无关，改由内容指纹判定（口径单源见探针脚本）。
            match erbc_digest_probe(wf) {
                Ok(true) => {
                    if corpus_ok {
                        fresh = true;
                        wf.em.line(&format!(
                            "现成 program.erbc = {}（新鲜：compiler-digest 与 corpus-patches 资产指纹均与清单一致，复用；跳过 6 分钟重编）",
                            p.display()));
                    } else {
                        wf.em.line("语料比 program.erbc 新 —— 重编（编译器源码没变，仍需重吃语料）");
                    }
                }
                Ok(false) => {
                    wf.em.line("compiler-digest 或 corpus-patches 资产指纹与清单不符 —— 重编，避免出包期 CMP-66 / 补丁指纹闸拒包");
                }
                Err(e) => {
                    // fail-safe：探针不可用就退回旧口径，宁可多重编也不放过一个哑包。
                    wf.em.line(&format!("⚠ {}；退回 mtime 兜底判据", e));
                    let compiler_roots = [
                        wf.app.join("core-vm").join("src").join("main").join("kotlin"),
                        wf.app.join("tools").join("erbcc").join("src").join("main").join("kotlin"),
                    ];
                    let compiler = compiler_roots
                        .iter()
                        .map(|q| newest_mtime_below(q, 8))
                        .max()
                        .unwrap_or(SystemTime::UNIX_EPOCH);
                    fresh = !wf.code_changed && pm >= corpus && pm >= compiler;
                    wf.em.line(&format!("现成 program.erbc = {}（{}，mtime 兜底）",
                        p.display(), if fresh { "新鲜，复用" } else { "过期，重编" }));
                }
            }
        }
    }
    if !fresh {
        if existing.is_none() {
            wf.em.line("没有随包程序产物——这个包装上会「启动失败：缺产物」，必须补编");
        }
        let g = win_slash(&wf.gradle_bat);
        let n_degraded = degraded_allowlist_count(wf);
        let mut args: Vec<String> = vec![
            "/c".into(), g, "--configure-on-demand".into(),
            ":tools:erbcc:compileCorpus".into(),
        ];
        if n_degraded > 0 {
            args.push("-PcompileMode=compat".into());
            args.push(format!("-PallowParseFailures=devtools/erbc-degraded-allowlist.tsv"));
            wf.em.line(&format!("erbc 编译档：compat + 容错清单（在册 {} 条降级）", n_degraded));
        } else {
            wf.em.line("erbc 编译档：strict（降级清单在册 0 条，不容许任何解析失败）");
        }
        run_ok(wf, "cmd", &args, &wf.app).map_err(|e| format!(
            "erbc 编译失败：{}\nstrict 档不容许解析失败；确系第三方源问题请在 devtools/erbc-degraded-allowlist.tsv 逐条登记并注明原因（工具会自动切 compat）。", e
        ))?;
    }
    let p = find_program_erbc(&wf.app).ok_or("编译结束仍找不到 program.erbc，无法出包。")?;
    let dir = p.parent().unwrap().to_path_buf();
    wf.erbc_dir = dir.clone();
    wf.em.line(&format!("ERATW_ERBC_DIR = {}", dir.display()));
    Ok(!fresh)
}

fn diagnose(out: &str) -> String {
    let hints: &[(&str, &str)] = &[
        ("journal-1.lock", "Gradle 缓存锁被占：确认没有别的构建后重跑（工具会自动停空闲 daemon）。"),
        ("fileHashes.lock", "caches/8.10.2 下的锁被空闲 daemon 独占：工具会全量扫锁并停空闲 daemon（实测撞过一次）。"),
        ("fileContent.lock", "caches/8.10.2 下的锁被空闲 daemon 独占：工具会全量扫锁并停空闲 daemon。"),
        ("generated-gradle-jars.lock", "caches/8.10.2 下的锁被空闲 daemon 独占：工具会全量扫锁并停空闲 daemon。"),
        ("ValueSnapshotter", "Gradle 启动期被锁文件拒绝访问：不是代码问题，释放缓存锁后重出。"),
        ("拒绝访问", "有文件被独占（多为 Gradle 缓存锁）：工具已把锁检查扩到 caches/ 全树。"),
        ("Timeout waiting to lock", "Gradle 缓存锁被占：确认没有别的构建后重跑。"),
        ("erbc-freshness", "随包 erbc 不新鲜：重编 :tools:erbcc:compileCorpus（工具已自动处理一次）。"),
        ("CMP-66", "随包字节码的 compiler-digest 与当前编译器源码树不符（常见：拉到了新提交）。重编 erbc 后重出。"),
        ("新鲜度对账失败", "CMP-66 新鲜度闸拒绝：强制重编 :tools:erbcc:compileCorpus（工具已自动处理一次）。"),
        ("启动失败：缺产物", "缺随包 erbc：编语料产物后重出（工具已自动处理一次）。"),
        ("token", "端点/令牌不对：检查 eratw-android/local.report.properties。"),
        ("令牌", "端点/令牌不对：检查 eratw-android/local.report.properties。"),
        ("退役", "语料根指到旧 4.980：必须指向 4.981（工具已自动校准）。"),
        ("debug keystore", "签名章退回 debug：检查 .tooling/gradle-home/gradle.properties 四键。"),
        ("resValues", "gradle 缓存旧：clean 后重出，或加 ERATW_GRADLE_EXTRA_ARGS=--rerun-tasks。"),
        ("apk-version-code", "发版单调闸：本地 versionCode 必须严格大于线上 manifest，用 --version 抬号。"),
        ("sign-key", "清单签名私钥缺失或与设备内置公钥不配对：查 local.update-signing/k1-private.pk8.pem。"),
        ("INSTALL_FAILED", "装机失败：看 adb 输出；签名不一致时工具绝不自动卸载（会清存档）。"),
        ("No space left", "磁盘满了：清 build/ 旧归档与 Gradle 缓存后重跑。"),
        ("OutOfMemoryError", "Gradle/JVM 内存不足：关掉并行构建或调 org.gradle.jvmargs。"),
        ("Could not resolve", "依赖解析失败：网络或 Gradle 缓存问题，直连失败可重试。"),
        ("sha256", "归档哈希对账失败：文件可能被替换/损坏，拒绝发布。"),
    ];
    let mut s = String::new();
    for (needle, hint) in hints {
        if out.contains(needle) {
            s.push_str(&format!("\n· {} → {}", needle, hint));
        }
    }
    if s.is_empty() {
        s.push_str("\n· 未匹配到已知故障，请看上方日志末尾输出。");
    }
    format!("{}\n\n末尾日志：\n{}", s, tail(out, 25))
}

fn step_pack(wf: &mut Wf) -> Result<(), String> {
    wf.em.stage(42, &format!("出包 + 五步体检（{}，预计 1~12 分钟）", wf.opts.mode.short()));
    for attempt in 1..=2 {
        check_cancel()?;
        if attempt == 1 { wf.started = Instant::now(); }
        let mut args: Vec<String> = wf.opts.mode.pack_args(degraded_allowlist_count(wf) > 0);
        // 显式给游戏根：worktree / 不同机器下不依赖脚本的主机名回落。
        args.push("--game-root".into());
        args.push(win_slash(&wf.game_root));
        wf.em.line(&format!("第 {} 次出包：mode={} args={:?}", attempt, wf.opts.mode.short(), args));
        let pyargs = py_args(wf, "devtools/pack_release.py", &args);
        let (code, out) = execute(wf, &wf.python, &pyargs, &wf.app)?;
        if code == 0 {
            wf.em.line("构建 + 五步体检全部通过");
            return Ok(());
        }
        wf.em.line(&format!("出包失败（exit {}）", code));
        if attempt == 1 {
            let lockish = out.contains("journal-1.lock") || out.contains("Timeout waiting to lock")
                || out.contains("fileHashes.lock") || out.contains("fileContent.lock")
                || out.contains("generated-gradle-jars.lock") || out.contains("ValueSnapshotter")
                || (out.contains(".lock") && out.contains("拒绝访问"));
            let erbcish = out.contains("erbc-freshness") || out.contains("缺产物") || out.contains("program.erbc")
                || out.contains("CMP-66") || out.contains("新鲜度对账失败") || out.contains("产物由旧编译器产出")
                || out.contains("compileCorpus");
            if lockish {
                wf.em.line("自愈：释放 Gradle 缓存锁后重试一次");
                let _ = release_locks(wf, 50, "自愈：重新释放 Gradle 缓存锁");
                continue;
            }
            if erbcish {
                wf.em.line("自愈：强制重编 erbc 后重试一次");
                let _ = step_erbc(wf, true)?;
                release_locks(wf, 58, "释放重编 erbc 占用的 Gradle 缓存锁")?;
                wf.em.stage(60, "重出包（自愈重试）");
                continue;
            }
        }
        return Err(format!("出包失败（exit {}）：{}", code, diagnose(&out)));
    }
    Err("出包两次失败，已停止。".into())
}

fn newest_apk(app: &Path, since: SystemTime) -> Option<PathBuf> {
    let dir = app.join("build").join("release-artifacts");
    let mut best: Option<(SystemTime, PathBuf)> = None;
    if let Ok(rd) = fs::read_dir(&dir) {
        for e in rd.flatten() {
            let p = e.path();
            if p.extension().map(|x| x == "apk").unwrap_or(false) {
                let t = fs::metadata(&p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH);
                if t + Duration::from_secs(4) >= since && best.as_ref().map(|b| t > b.0).unwrap_or(true) {
                    best = Some((t, p));
                }
            }
        }
    }
    if let Some((_, p)) = best { return Some(p); }
    // 兜底也必须是「本次新生成」的，否则会把上一版 APK 当成新版发出去。
    let fallback = app.join("app").join("build").join("outputs").join("apk").join("release").join("app-release.apk");
    if fallback.is_file() {
        let t = fs::metadata(&fallback).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH);
        if t + Duration::from_secs(4) >= since { return Some(fallback); }
    }
    None
}

fn step_archive(wf: &mut Wf) -> Result<PathBuf, String> {
    check_cancel()?;
    wf.em.stage(82, "归档对账（文件名 / 体积 / sha256 实算）");
    let since = SystemTime::now() - wf.started.elapsed() - Duration::from_secs(30);
    let apk = newest_apk(&wf.app, since)
        .ok_or("体检通过了却找不到本次新归档的 APK（build/release-artifacts/ 下没有新 .apk）。")?;
    if !apk_name_matches(wf, &apk) {
        return Err(format!(
            "归档名 {} 与本次版本 {}+{} 不符 —— 可能复用了旧包，拒绝交付/发布。",
            apk.file_name().unwrap_or_default().to_string_lossy(), wf.version_name, wf.version_code
        ));
    }
    let size = fs::metadata(&apk).map(|m| m.len()).unwrap_or(0);
    if size < 1024 * 1024 {
        return Err(format!("归档体积异常（{} B），疑似损坏，拒绝交付/发布。", size));
    }
    let sha = ensure_sha256(wf, &apk)?;
    wf.apk_sha = sha.clone();
    wf.apk_path = apk.clone();
    wf.em.line(&format!("APK  = {}", apk.display()));
    wf.em.line(&format!("大小 = {:.1} MiB ({} B)", size as f64 / 1048576.0, size));
    wf.em.line(&format!("sha256 = {}（已实算复核）", sha));
    *LAST_ARCHIVE.lock().unwrap() = Some(apk.to_string_lossy().to_string());
    Ok(apk)
}

fn step_upload(wf: &mut Wf, apk: &Path) -> Result<(), String> {
    if wf.opts.mode != Mode::Release || !wf.opts.upload {
        wf.em.line("本次不上传镜像/更新清单（按包型或勾选）");
        return Ok(());
    }
    if wf.opts.headless && !wf.opts.confirm_upload {
        wf.em.line("按「上传前二次确认」纪律：headless 未带 --yes-upload，本次只出包、不动线上清单。");
        wf.em.line("确认要发版时加 --yes-upload 重跑即可。");
        return Ok(());
    }
    check_cancel()?;
    wf.em.stage(90, "发版：emit_update --upload");
    // 私钥在共享根 local.update-signing/（不入库）；脚本 cwd=eratw-android，必须给绝对路径。
    let key = find_sign_key(wf).ok_or_else(|| format!(
        "找不到清单签名私钥（{} / {} / {} 下的 local.update-signing/）。\nemit_update 会拒发未签名清单；本工具刻意不提供 --unsigned-explicit 逃生门。",
        wf.tooling_root.display(), wf.root.display(), wf.app.display()
    ))?;
    wf.em.line(&format!("清单签名私钥 = {}（内容不回显）", key.display()));
    let note = if wf.opts.note.trim().is_empty() {
        format!("常规更新 {}", wf.version_name)
    } else {
        wf.opts.note.trim().to_string()
    };
    let args = vec![
        "--upload".into(),
        "--apk".into(), win_slash(apk),
        "--game-root".into(), win_slash(&wf.game_root),
        "--erbc-dir".into(), win_slash(&wf.erbc_dir),
        "--note".into(), note,
        "--sign-key".into(), win_slash(&key),
    ];
    let pyargs = py_args(wf, "devtools/emit_update.py", &args);
    let mut last_out = String::new();
    for attempt in 1..=2 {
        let (code, out) = execute(wf, &wf.python, &pyargs, &wf.app)?;
        if code == 0 {
            wf.em.line("发版上传完成（线上清单已更新）");
            verify_online_manifest(wf);
            return Ok(());
        }
        last_out = out;
        if attempt == 1 {
            wf.em.line("发版失败，5 秒后重试一次（网络抖动常见）…");
            thread::sleep(Duration::from_secs(5));
            check_cancel()?;
        }
    }
    Err(format!(
        "发版上传失败：{}\n常见原因：本地 versionCode 没有严格大于线上 manifest（需抬号）、R2 三键缺失、清单签名私钥与设备内置公钥不配对、网络不通。",
        diagnose(&last_out)
    ))
}

fn step_github_release(wf: &mut Wf, apk: &Path) -> Result<(), String> {
    if !wf.opts.github_release { return Ok(()); }
    if wf.opts.mode != Mode::Release {
        wf.em.line("⚠ 只有正式包才发 GitHub Release，本次跳过");
        return Ok(());
    }
    check_cancel()?;
    wf.em.stage(93, "发布 GitHub Release（公开仓）");
    // 本机经验：github.com:443 直连超时、沙箱代理 CONNECT 502，
    // 但 api.github.com / uploads.github.com 可达 —— 全程走 gh CLI / API，绝不 git push。
    let repo = wf.opts.github_repo.clone();
    let tag = format!("v{}", wf.version_name);
    let title = format!("EraTW Pocket {}", wf.version_name);
    let notes = notes_path(wf)?;
    // 归档名带 -dirty/git7/sha 不适合玩家：上传时直接用 #友好名，天然幂等（--clobber）。
    let friendly = format!("eratw-{}+{}-full.apk", wf.version_name, wf.version_code);
    let asset = format!("{}#{}", win_slash(apk), friendly);
    wf.em.line(&format!("Release 说明 = {} · 附件名 = {}", notes.display(), friendly));

    let (vc, _) = execute(wf, "gh", &sargs(&["release", "view", &tag, "-R", &repo]), &wf.app)?;
    let make: Vec<String> = if vc == 0 {
        vec!["release".into(), "edit".into(), tag.clone(), "-R".into(), repo.clone(),
             "--title".into(), title.clone(), "--notes-file".into(), win_slash(&notes)]
    } else {
        vec!["release".into(), "create".into(), tag.clone(), "-R".into(), repo.clone(),
             "--title".into(), title.clone(), "--notes-file".into(), win_slash(&notes)]
    };
    let (mc, mo) = execute(wf, "gh", &make, &wf.app)?;
    if mc != 0 { return Err(format!("gh release create/edit 失败：{}", tail(&mo, 12))); }

    let up: Vec<String> = vec!["release".into(), "upload".into(), tag.clone(),
        "-R".into(), repo.clone(), asset, "--clobber".into()];
    let mut last = String::new();
    let mut uploaded = false;
    for attempt in 1..=2 {
        let (uc, uo) = execute(wf, "gh", &up, &wf.app)?;
        if uc == 0 { uploaded = true; break; }
        last = uo;
        if attempt == 1 { wf.em.line("附件上传失败，5 秒后重试一次…"); thread::sleep(Duration::from_secs(5)); }
    }
    if !uploaded { return Err(format!("GitHub Release 附件上传失败：{}", tail(&last, 12))); }

    // 只补中文显示名（不改名，避免重复附件 / 改错对象），按名字精确定位 asset。
    let jq = format!("[.assets[] | select(.name==\"{}\") | .id] | .[0]", friendly);
    let q: Vec<String> = vec!["api".into(), format!("repos/{}/releases/tags/{}", repo, tag),
        "--jq".into(), jq];
    if let Ok((qc, qo)) = execute(wf, "gh", &q, &wf.app) {
        if qc == 0 {
            let aid = qo.trim().lines().last().unwrap_or("").trim().to_string();
            if !aid.is_empty() && aid.chars().all(|c| c.is_ascii_digit()) {
                let patch: Vec<String> = vec!["api".into(), "-X".into(), "PATCH".into(),
                    format!("repos/{}/releases/assets/{}", repo, aid),
                    "-f".into(), format!("label=EraTW Pocket {} 全量包", wf.version_name),
                    "--jq".into(), ".browser_download_url".into()];
                if let Ok((pc, po)) = execute(wf, "gh", &patch, &wf.app) {
                    if pc == 0 {
                        let url = po.trim().lines().last().unwrap_or("").trim().trim_matches('"').to_string();
                        if !url.is_empty() { wf.em.line(&format!("附件下载 = {}", url)); }
                    }
                }
            }
        }
    }
    let html: Vec<String> = vec!["api".into(), format!("repos/{}/releases/tags/{}", repo, tag),
        "--jq".into(), ".html_url".into()];
    if let Ok((hc, ho)) = execute(wf, "gh", &html, &wf.app) {
        if hc == 0 {
            wf.github_url = ho.trim().lines().last().unwrap_or("").trim().trim_matches('"').to_string();
        }
    }
    if wf.github_url.is_empty() {
        wf.github_url = format!("https://github.com/{}/releases/tag/{}", repo, tag);
    }
    wf.em.line(&format!("GitHub Release 已发布 = {}", wf.github_url));
    Ok(())
}

fn step_install(wf: &mut Wf, apk: &Path) -> Result<(), String> {
    if !wf.opts.install {
        wf.em.line("跳过装机（按勾选）");
        return Ok(());
    }
    wf.em.stage(96, "装到已连接的设备");
    if !wf.adb.is_file() {
        wf.em.line("⚠ 找不到 adb.exe，跳过装机");
        return Ok(());
    }
    let adb = win_slash(&wf.adb);
    let (_, out) = execute(wf, &adb, &sargs(&["devices", "-l"]), &wf.app)?;
    let mut devs: Vec<String> = out.lines()
        .filter(|l| l.contains("\tdevice"))
        .filter_map(|l| l.split_whitespace().next().map(|s| s.to_string()))
        .collect();
    devs.sort();
    devs.dedup();
    let dev = if !wf.opts.device.is_empty() {
        wf.opts.device.clone()
    } else if devs.len() == 1 {
        devs[0].clone()
    } else if devs.is_empty() {
        wf.em.line("⚠ 没有已授权的设备（adb devices 为空），跳过装机；APK 已归档可直接拷走。");
        return Ok(());
    } else {
        wf.em.line(&format!("⚠ 检测到 {} 台设备（{}）且未用 --device 指定，跳过装机避免装错机器。", devs.len(), devs.join(", ")));
        return Ok(());
    };
    if !devs.contains(&dev) {
        wf.em.line(&format!("⚠ --device {} 不在 adb devices 的已授权列表里，跳过装机。", dev));
        return Ok(());
    }
    wf.em.line(&format!("设备 = {}", dev));
    let (code, out) = execute(wf, &adb,
        &["-s".into(), dev.clone(), "install".into(), "-r".into(), win_slash(apk)], &wf.app)?;
    if code != 0 {
        if out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            wf.em.line(&format!("⚠ 签名章不一致，系统拒绝覆盖安装。本工具不会自动卸载（会清存档与已解包资源）。\n需要卸载时手工执行：adb uninstall {}", PACKAGE_NAME));
        } else if out.contains("INSTALL_FAILED_VERSION_DOWNGRADE") {
            wf.em.line("⚠ 目标机已装更高 versionCode，拒绝降级安装（想装测试包请用 --version 抬号或先看设备版本）。");
        } else {
            wf.em.line(&format!("⚠ 装机失败（不阻断交付）：{}", tail(&out, 6)));
        }
        return Ok(());
    }
    let (_, dump) = execute(wf, &adb,
        &sargs(&["-s", &dev, "shell", "dumpsys", "package", PACKAGE_NAME]), &wf.app)?;
    let got_code = dump.lines().find(|l| l.trim().starts_with("versionCode="))
        .and_then(|l| l.split('=').nth(1)).map(|x| x.trim().split_whitespace().next().unwrap_or("").to_string()).unwrap_or_default();
    let got_name = dump.lines().find(|l| l.trim().starts_with("versionName="))
        .and_then(|l| l.split('=').nth(1)).map(|x| x.trim().to_string()).unwrap_or_default();
    if !got_code.is_empty() && got_code != wf.version_code.to_string() {
        wf.em.line(&format!("⚠ 装机后版本对不上：设备 versionCode={} / 期望 {}（可能装到了别的包）", got_code, wf.version_code));
        return Ok(());
    }
    wf.install_ok = true;
    let _ = execute(wf, &adb, &sargs(&["-s", &dev, "shell", "monkey", "-p", PACKAGE_NAME, "-c", "android.intent.category.LAUNCHER", "1"]), &wf.app);
    wf.em.line(&format!("安装成功并拉起：设备 versionName={} / versionCode={}", got_name, got_code));
    Ok(())
}

fn run_workflow(wf: &mut Wf) -> Result<String, String> {
    check_cancel()?;
    wf.em.line(&format!("=== eratw 一键出包 v1.3 · {} · {} ===", wf.opts.mode.label(), now_stamp()));
    wf.em.line("自动完成：拉代码 / 4.981 语料 / 补配置 / 发版预检 / Gradle 锁 / 兼容档 erbc / 出包体检 / 归档对账 / 发版 / 装机");
    step_toolchain(wf)?;
    step_git(wf)?;
    step_corpus(wf)?;
    step_config(wf)?;
    step_preflight(wf)?;
    if !wf.opts.publish_only {
        step_lock(wf)?;
    } else {
        wf.em.line("只发版模式：不跑 Gradle，跳过缓存锁检查");
    }
    if wf.opts.selftest {
        wf.em.stage(100, "预检完成（selftest：不构建）");
        let mut s = format!(
            "✅ 预检全绿\n仓库：{}\n工具链：{}\n版本：{} · HEAD {}\n语料：{}\n包型：{}",
            wf.root.display(), wf.tooling_root.display(), wf.version, wf.git7,
            wf.game_root.display(), wf.opts.mode.short()
        );
        if !wf.checks.is_empty() {
            s.push_str("\n发版闸：");
            s.push_str(&wf.checks.join(" / "));
        }
        return Ok(s);
    }
    if wf.opts.publish_only {
        wf.em.stage(50, "只发版：复用最新归档，不重新构建");
        let erbc = find_program_erbc(&wf.app).ok_or("找不到 program.erbc，无法给数据包带新字节码。")?;
        wf.erbc_dir = erbc.parent().unwrap().to_path_buf();
        let apk = newest_apk(&wf.app, SystemTime::UNIX_EPOCH)
            .ok_or("build/release-artifacts/ 里没有可发布的 APK。")?;
        if !apk_name_matches(wf, &apk) {
            return Err(format!("最新归档 {} 与版本 {}+{} 不符，拒绝补发旧包。\n要发别的版本请加 --version 或先出包。",
                apk.file_name().unwrap_or_default().to_string_lossy(), wf.version_name, wf.version_code));
        }
        wf.apk_path = apk.clone();
        wf.apk_sha = ensure_sha256(wf, &apk)?;
        wf.em.line(&format!("复用归档 = {}", apk.display()));
        step_upload(wf, &apk)?;
        step_github_release(wf, &apk)?;
        wf.em.stage(100, "发版完成（未重新构建）");
        let gh = if wf.github_url.is_empty() { "未发布".to_string() } else { wf.github_url.clone() };
        return Ok(format!(
            "✅ 已发版（复用已有归档，未重新构建）\nAPK：{}\n版本：{} · HEAD {}\n线上清单：{}（复核 {}）\nGitHub Release：{}",
            apk.display(), wf.version, wf.git7,
            if wf.opts.upload { "已上传" } else { "未改动" },
            if wf.online_ok { "通过" } else { "未通过/未执行" }, gh
        ));
    }
    step_bump(wf)?;
    let erbc_rebuilt = step_erbc(wf, false)?;
    // compileCorpus 刚用过的 daemon 会独占 caches/8.10.2/ 下三个锁；
    // 不先释放，紧接着的 pack_release 在 Gradle 启动阶段就会撞锁（实测 exit 1）。
    // 但 erbc 复用时本轮根本没起过 compileCorpus，第二次全量锁扫描纯属白跑，直接跳过。
    if erbc_rebuilt {
        release_locks(wf, 40, "释放 erbc 编译占用的 Gradle 缓存锁")?;
    } else {
        wf.em.stage(40, "erbc 复用，无需释放编译锁");
        wf.em.line("本轮未启动 compileCorpus，跳过第二次缓存锁扫描");
    }
    step_pack(wf)?;
    let apk = step_archive(wf)?;
    step_upload(wf, &apk)?;
    step_github_release(wf, &apk)?;
    step_install(wf, &apk)?;
    wf.em.stage(100, "全部完成");
    let mut s = format!("✅ {} 已出（{} / {}）\nAPK：{}\nsha256：{}\n",
        wf.opts.mode.short(), wf.version, wf.git7, apk.display(), wf.apk_sha);
    s.push_str(&format!("日志：{}\n", wf.em.log_path.display()));
    if wf.opts.mode == Mode::Release && wf.opts.upload && wf.opts.confirm_upload {
        s.push_str(&format!("线上更新清单：已上传（复核 {}）\n", if wf.online_ok { "通过" } else { "未通过" }));
    } else {
        s.push_str("线上更新清单：未改动\n");
    }
    s.push_str(&format!("装机：{}\n", if wf.install_ok { "成功并拉起" } else { "未执行/跳过/失败" }));
    if wf.opts.mode == Mode::Dev {
        s.push_str("注意：测试包是无随包条目的小包，只能装在已解包过数据的机器上。\n");
    }
    if !wf.github_url.is_empty() {
        s.push_str(&format!("\nGitHub Release：{}", wf.github_url));
    }
    s.push_str("对外发布时请记得告知玩家「崩溃报告会自动上报」。");
    Ok(s)
}

// --------------------------------------------------------------------- GUI

// 观察窗：按字节增量读 CLI 进程写的日志，解析 >>> [xx%] 阶段行与结束标记。
fn poll_follow_log(hwnd: HWND) {
    let mut guard = match FOLLOW.lock() { Ok(g) => g, Err(_) => return };
    let st = match guard.as_mut() { Some(s) => s, None => return };
    if st.done { return; }
    let mut f = match File::open(&st.path) { Ok(f) => f, Err(_) => return };
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    if len <= st.offset { return; }
    if f.seek(SeekFrom::Start(st.offset)).is_err() { return; }
    let mut buf: Vec<u8> = Vec::new();
    if f.read_to_end(&mut buf).is_err() { return; }
    st.offset = len;
    st.pending.push_str(&String::from_utf8_lossy(&buf));
    let mut lines: Vec<String> = Vec::new();
    while let Some(pos) = st.pending.find('\n') {
        let line: String = st.pending.drain(..=pos).collect();
        // 子进程输出里的 CR 会让 EDIT 控件回车覆盖上一行，统一清掉。
        lines.push(line.trim_end_matches(['\r', '\n']).replace('\r', " "));
    }
    for line in lines {
        if let Some(rest) = line.strip_prefix("===ERATW_PACK_DONE=== ") {
            st.done = true;
            let ok = rest.trim() == "OK";
            unsafe {
                let status = wide(if ok { "✅ 全部完成 · 窗口可随时关闭" } else { "❌ 失败 · 看日志区末尾" });
                SetWindowTextW(GetDlgItem(hwnd, ID_STATUS), status.as_ptr());
                let stage = wide(if ok { "全部完成" } else { "失败" });
                SetWindowTextW(GetDlgItem(hwnd, ID_STAGE), stage.as_ptr());
                SendMessageW(GetDlgItem(hwnd, ID_PROGRESS), PBM_SETPOS, if ok { 100 } else { 0 }, 0);
                KillTimer(hwnd, TIMER_ID);
            }
        } else if let Some(rest) = line.strip_prefix("===ERATW_PACK_SUMMARY=== ") {
            append_log(hwnd, &format!("结果：{}", rest));
        } else if line.starts_with(">>> [") {
            let inner = &line[5..];
            if let Some(end) = inner.find(']') {
                let pct = inner[..end].trim().trim_end_matches('%').trim().parse::<usize>().unwrap_or(0);
                let text = inner[end + 1..].trim();
                unsafe {
                    let stg = wide(text);
                    SetWindowTextW(GetDlgItem(hwnd, ID_STAGE), stg.as_ptr());
                    SetWindowTextW(GetDlgItem(hwnd, ID_STATUS), stg.as_ptr());
                    SendMessageW(GetDlgItem(hwnd, ID_PROGRESS), PBM_SETPOS, pct.min(100), 0);
                }
            }
            append_log(hwnd, &line);
        } else {
            append_log(hwnd, &line);
        }
    }
}

fn make_ctrl(
    parent: HWND,
    class: &str,
    text: &str,
    style: DWORD,
    ex: DWORD,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    id: i32,
    font: HFONT,
) -> HWND {
    let cls = wide(class);
    let txt = wide(text);
    let h = unsafe {
        CreateWindowExW(
            ex,
            cls.as_ptr(),
            txt.as_ptr(),
            WS_CHILD | WS_VISIBLE | style,
            x,
            y,
            w,
            h,
            parent,
            id as HMENU,
            GetModuleHandleW(std::ptr::null()),
            std::ptr::null(),
        )
    };
    if h != 0 {
        unsafe {
            SendMessageW(h, WM_SETFONT, font as WPARAM, 1);
        }
    }
    h
}

fn set_check(hwnd: HWND, id: i32, on: bool) {
    unsafe {
        SendMessageW(GetDlgItem(hwnd, id), BM_SETCHECK, if on { 1 } else { 0 }, 0);
    }
}

fn is_checked(hwnd: HWND, id: i32) -> bool {
    unsafe { SendMessageW(GetDlgItem(hwnd, id), BM_GETCHECK, 0, 0) == BST_CHECKED }
}

fn get_text(hwnd: HWND, id: i32) -> String {
    let h = unsafe { GetDlgItem(hwnd, id) };
    let mut buf = [0u16; 512];
    let n = unsafe { GetWindowTextW(h, buf.as_mut_ptr(), buf.len() as i32) };
    if n <= 0 {
        return String::new();
    }
    let n = n as usize;
    String::from_utf16_lossy(&buf[..n.min(buf.len())])
}

fn append_log(hwnd: HWND, s: &str) {
    let h = unsafe { GetDlgItem(hwnd, ID_EDIT_LOG) };
    let mut t = wide(s);
    // 末尾加 \r\n
    t.pop();
    t.push(0x0D);
    t.push(0x0A);
    t.push(0);
    unsafe {
        SendMessageW(h, EM_SETSEL, usize::MAX, -1);
        SendMessageW(h, EM_REPLACESEL, 0, t.as_ptr() as LPARAM);
    }
}

fn msgbox(hwnd: HWND, text: &str, caption: &str, flags: UINT) -> i32 {
    let t = wide(text);
    let c = wide(caption);
    unsafe { MessageBoxW(hwnd, t.as_ptr(), c.as_ptr(), flags) }
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: UINT, wp: WPARAM, lp: LPARAM) -> LRESULT {
    match msg {
        WM_COMMAND => {
            let id = (wp & 0xFFFF) as i32;
            let code = ((wp >> 16) & 0xFFFF) as u32;
            if code == BN_CLICKED {
                match id {
                    ID_START => {
                        if RUNNING.load(Ordering::SeqCst) {
                            return 0;
                        }
                        let mode = if is_checked(hwnd, ID_MODE_RELEASE) {
                            Mode::Release
                        } else if is_checked(hwnd, ID_MODE_DEV) {
                            Mode::Dev
                        } else {
                            Mode::TestRelease
                        };
                        let upload = is_checked(hwnd, ID_CHK_UPLOAD);
                        if mode == Mode::Release && upload {
                            let q = wide(&format!(
                                "即将出【正式包】并上传到线上更新清单（emit_update --upload）。\n\n这会改动玩家看到的更新源，确定继续？\n\n只想要本地测试包请改选「测试用正式包」。"
                            ));
                            let cap = wide("发版二次确认");
                            let r = MessageBoxW(hwnd, q.as_ptr(), cap.as_ptr(), MB_YESNO | MB_ICONWARNING | MB_TOPMOST);
                            if r != IDYES {
                                return 0;
                            }
                        }
                        let opts = Opts {
                            mode,
                            sync_git: is_checked(hwnd, ID_CHK_GIT),
                            install: is_checked(hwnd, ID_CHK_INSTALL),
                            upload,
                            force_erbc: is_checked(hwnd, ID_CHK_FORCE_ERBC),
                            note: get_text(hwnd, ID_EDIT_NOTE),
                            bump_version: get_text(hwnd, ID_EDIT_VERSION),
                            confirm_upload: true,
                            publish_only: false,
                            headless: false,
                            selftest: false,
                            root_override: String::new(),
                            github_release: mode == Mode::Release && upload,
                            github_repo: GITHUB_REPO_DEFAULT.to_string(),
                            notes_file: String::new(),
                            no_window: false,
                            follow_gui: String::new(),
                            follow_title: String::new(),
                            device: String::new(),
                            timeout_secs: 45 * 60,
                        };
                        let logedit = GetDlgItem(hwnd, ID_EDIT_LOG);
                        SetWindowTextW(logedit, wide("").as_ptr());
                        EnableWindow(GetDlgItem(hwnd, ID_START), 0);
                        EnableWindow(GetDlgItem(hwnd, ID_CANCEL), 1);
                        CANCEL.store(false, Ordering::SeqCst);
                        RUNNING.store(true, Ordering::SeqCst);
                        let h = hwnd as usize;
                        thread::spawn(move || {
                            let res = build_and_run(h, opts.clone(), make_log_path(&opts));
                            unsafe {
                                let p: *mut Result<String, String> = Box::into_raw(Box::new(res));
                                PostMessageW(h as HWND, WM_APP_DONE, 0, p as LPARAM);
                            }
                        });
                    }
                    ID_CANCEL => {
                        CANCEL.store(true, Ordering::SeqCst);
                        let pid = CUR_PID.load(Ordering::SeqCst);
                        if pid != 0 {
                            let kill: Vec<String> = vec!["/PID".into(), pid.to_string(), "/T".into(), "/F".into()];
                            let _ = Command::new("taskkill").args(&kill).output();
                        }
                    }
                    ID_OPEN => {
                        let dir = LAST_ARCHIVE.lock().unwrap().clone();
                        let p = dir.map(PathBuf::from).unwrap_or_else(|| {
                            // 归档目录：从 exe 位置找仓库根
                            let o = Opts::default();
                            resolve_root(&o).unwrap_or_else(|_| PathBuf::from(".")).join("eratw-android").join("build").join("release-artifacts")
                        });
                        let parent = if p.is_file() { p.parent().unwrap_or(&p).to_path_buf() } else { p };
                        let _ = Command::new("explorer").arg(parent).spawn();
                    }
                    _ => {}
                }
            }
            0
        }
        WM_TIMER => {
            if wp == TIMER_ID {
                poll_follow_log(hwnd);
            }
            0
        }
        WM_APP_LOG => {
            let p = lp as *mut String;
            if !p.is_null() {
                let s = Box::from_raw(p);
                append_log(hwnd, &s);
            }
            0
        }
        WM_APP_STAGE => {
            let p = lp as *mut String;
            if !p.is_null() {
                let s = Box::from_raw(p);
                let stage = wide(&s);
                SetWindowTextW(GetDlgItem(hwnd, ID_STAGE), stage.as_ptr());
                let status = wide(&s);
                SetWindowTextW(GetDlgItem(hwnd, ID_STATUS), status.as_ptr());
                SendMessageW(GetDlgItem(hwnd, ID_PROGRESS), PBM_SETPOS, wp.min(100), 0);
            }
            0
        }
        WM_APP_DONE => {
            let p = lp as *mut Result<String, String>;
            let res = if p.is_null() {
                Err("窗口消息丢失".to_string())
            } else {
                *Box::from_raw(p)
            };
            RUNNING.store(false, Ordering::SeqCst);
            EnableWindow(GetDlgItem(hwnd, ID_START), 1);
            EnableWindow(GetDlgItem(hwnd, ID_CANCEL), 0);
            let (ok, body) = match res {
                Ok(s) => (true, s),
                Err(e) => (false, e),
            };
            let status = wide(if ok { "完成 · 可以关掉窗口了" } else { "失败 · 看日志区最后几行" });
            SetWindowTextW(GetDlgItem(hwnd, ID_STATUS), status.as_ptr());
            if !ok {
                SendMessageW(GetDlgItem(hwnd, ID_PROGRESS), PBM_SETPOS, 0, 0);
            }
            let cap = wide(if ok { "出包完成" } else { "出包失败" });
            let t = wide(&body);
            MessageBoxW(hwnd, t.as_ptr(), cap.as_ptr(), if ok { MB_OK | MB_ICONINFORMATION } else { MB_OK | MB_ICONWARNING });
            0
        }
        WM_CLOSE => {
            if RUNNING.load(Ordering::SeqCst) {
                let q = wide("正在出包，确定要关掉窗口吗？（子进程可能还在跑）");
                let c = wide("确认关闭");
                if MessageBoxW(hwnd, q.as_ptr(), c.as_ptr(), MB_YESNO | MB_ICONWARNING) != IDYES {
                    return 0;
                }
                CANCEL.store(true, Ordering::SeqCst);
            }
            DestroyWindow(hwnd);
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}

// 外层统一收口：Wf 构造之前的早期失败（--root 非法 / 工具链缺失 / 抢锁失败）
// 以前会直接 ? 返回，既不写 done marker 也不写结果 JSON，agent 只能靠猜。
fn build_and_run(hwnd_usize: usize, opts: Opts, log_path: PathBuf) -> Result<String, String> {
    let r = build_and_run_inner(hwnd_usize, opts.clone(), log_path.clone());
    ensure_terminal_result(&log_path, &opts, &r);
    write_done_marker(&log_path, &r);
    r
}

fn build_and_run_inner(hwnd_usize: usize, opts: Opts, log_path: PathBuf) -> Result<String, String> {
    let root = resolve_root(&opts)?;
    let app = root.join("eratw-android");
    let em = Emitter { hwnd: hwnd_usize as HWND, headless: opts.headless, log_path: log_path.clone() };
    let tooling_root = resolve_tooling_root(&root, &em)?;
    let tc = detect_toolchain(&tooling_root)?;
    // 锁放共享工具链根：不同 worktree 也共用同一 Gradle 缓存，必须互斥。
    let lock_path = tooling_root.join(".tooling").join("gradle-home").join(".eratw-pack.lock");
    let _ = fs::create_dir_all(lock_path.parent().unwrap_or(&app));
    let guard = if opts.selftest { None } else { Some(acquire_run_lock(&lock_path)?) };
    let (vn, vc, _) = parse_version_parts(&app);
    let mut wf = Wf {
        em: em.clone(),
        root,
        app,
        tooling_root,
        jdk_dir: tc.jdk,
        gradle_bin: tc.gradle_bin,
        gradle_home: tc.gradle_home,
        sdk_dir: tc.sdk,
        opts,
        python: "python".into(),
        gradle_bat: tc.gradle_bat,
        adb: tc.adb,
        game_root: PathBuf::new(),
        erbc_dir: PathBuf::new(),
        git7: String::new(),
        version: String::new(),
        version_name: vn,
        version_code: vc,
        apk_path: PathBuf::new(),
        apk_sha: String::new(),
        online_ok: false,
        install_ok: false,
        checks: Vec::new(),
        code_changed: false,
        started: Instant::now(),
        github_url: String::new(),
        _lock: guard,
    };
    let r = run_workflow(&mut wf);
    match &r {
        Ok(s) => em.log_only(s),
        Err(e) => em.log_only(&format!("❌ {}", e)),
    }
    write_result_json(&wf, &log_path, &r);
    r
}

fn need_val(args: &[String], i: &mut usize, flag: &str) -> Result<String, String> {
    if let Some(v) = args.get(*i + 1) {
        if !v.starts_with("--") {
            *i += 1;
            return Ok(v.clone());
        }
    }
    Err(format!("{} 缺少参数值", flag))
}

fn parse_cli() -> Result<Opts, String> {
    let mut o = Opts::default();
    o.timeout_secs = env::var("ERATW_PACK_TIMEOUT_MIN").ok()
        .and_then(|v| v.parse::<u64>().ok()).unwrap_or(45).saturating_mul(60);
    let args: Vec<String> = env::args().skip(1).collect();
    let mut i = 0;
    while i < args.len() {
        let a = args[i].as_str();
        match a {
            "--mode" | "-m" => {
                let v = need_val(&args, &mut i, a)?;
                o.mode = match v.as_str() {
                    "release" | "正式包" => Mode::Release,
                    "test-release" | "testrelease" | "测试用正式包" => Mode::TestRelease,
                    "dev" | "test" | "测试包" => Mode::Dev,
                    _ => return Err(format!("--mode 不认识的值：{}（可用 release / test-release / dev）", v)),
                };
            }
            "--root" => o.root_override = need_val(&args, &mut i, a)?,
            "--note" => o.note = need_val(&args, &mut i, a)?,
            "--version" | "-V" => o.bump_version = need_val(&args, &mut i, a)?,
            "--notes-file" => o.notes_file = need_val(&args, &mut i, a)?,
            "--github-repo" => o.github_repo = need_val(&args, &mut i, a)?,
            "--follow-gui" => o.follow_gui = need_val(&args, &mut i, a)?,
            "--title" => o.follow_title = need_val(&args, &mut i, a)?,
            "--device" => o.device = need_val(&args, &mut i, a)?,
            "--headless" => o.headless = true,
            "--github-release" => o.github_release = true,
            "--no-github-release" => o.github_release = false,
            "--no-window" => o.no_window = true,
            "--yes-upload" => o.confirm_upload = true,
            "--publish-only" => { o.publish_only = true; o.headless = true; }
            "--selftest" => { o.selftest = true; o.headless = true; }
            "--no-git" => o.sync_git = false,
            "--no-install" => o.install = false,
            "--no-upload" => o.upload = false,
            "--force-erbc" => o.force_erbc = true,
            "--help" | "-h" => { println!("{}", help_text()); std::process::exit(0); }
            _ => {
                if a.starts_with('-') {
                    return Err(format!("不认识的参数：{}（见 --help）", a));
                }
                return Err(format!("多余的位置参数：{}（工具不收位置参数，见 --help）", a));
            }
        }
        i += 1;
    }
    // 交叉校验：把「会白跑十分钟」的参数矛盾在这里一次性说清。
    if o.publish_only {
        if o.selftest { return Err("--publish-only 与 --selftest 不能同时用".into()); }
        o.mode = Mode::Release;
        if !o.bump_version.is_empty() {
            eprintln!("[warn] --publish-only 复用已有归档，--version 不会生效");
            o.bump_version.clear();
        }
    }
    if o.selftest { o.headless = true; }
    if !o.bump_version.is_empty() && o.mode != Mode::Release {
        eprintln!("[warn] 非 release 包型指定了 --version：只改本地工作树、不发版");
    }
    if o.github_release && o.mode != Mode::Release {
        eprintln!("[warn] 非 release 包型不会发 GitHub Release");
    }
    if !o.upload && o.confirm_upload {
        eprintln!("[warn] --no-upload 与 --yes-upload 同时给出，以 --no-upload 为准");
    }
    if !o.device.is_empty() && !o.install {
        eprintln!("[warn] 指定了 --device 但带 --no-install，设备参数不会生效");
    }
    if !o.notes_file.is_empty() && !o.github_release {
        eprintln!("[warn] 给了 --notes-file 但没开 --github-release，正文不会被使用");
    }
    Ok(o)
}

fn help_text() -> &'static str {
    "eratw 一键出包 v1.3 —— 任何调用方式都在桌面开实时进度窗（agent 调用也不例外）\n\
     双击 exe = 图形界面；给 agent 用：\n\
       eratw-pack.exe --headless --mode test-release   # 测试用正式包（不上传、不抬号）\n\
       eratw-pack.exe --headless --mode release --version 0.2.45 --yes-upload --github-release\n\
                                                 # 一条龙：抬号→出包→体检→R2→GitHub Release→装机\n\
       eratw-pack.exe --headless --mode dev            # 测试小包\n\
       eratw-pack.exe --selftest                      # 只预检（含发版闸），不构建\n\
     --version 0.2.45 = versionName 改 0.2.45、versionCode +1，写回 build.gradle.kts（幂等、拒回退）。\n\
     --yes-upload = headless 下对「上传线上清单」的显式确认（不带则只出包不上传）。\n\
     --github-release = 正式包额外发 GitHub Release（幂等；附件名自动友好化）。\n\
     --notes-file <md> = 正式发行汇报作 Release 正文；--github-repo <owner/repo> 可改仓。\n\
     --publish-only --yes-upload = 跳过构建，只把最新归档补发（不抬号）。\n\
     --root <代码根> = 指定 git worktree；工具链/语料自动去主工作树找。\n\
     --device <序列号> = 多设备时指定装机目标（不指定且多设备则跳过装机）。\n\
     --no-window = 无桌面/CI 时不弹窗（默认都弹）；窗口跟随 build/pack-one-*.log。\n\
     可选：--note --no-git --no-install --no-upload --force-erbc --no-github-release\n\
     环境：ERATW_PACK_TIMEOUT_MIN=45（单命令看门狗，0=不限时）、ERATW_TOOLING_ROOT、ERATW_ROOT。\n\
     结果 JSON 恒在日志同目录 build/pack-one-last.json，含 status=running/ok/failed 与 action。\n\
     action=selftest 只是预检通过、没有 APK；只有 action=build/publish-only 才是真的出包/发版。\n\
     开跑即作废上一次结果；失败、参数错误、进程被杀都不会残留假的 ok=true。\n\
     退出码 0 = 成功；1 = 流程失败；2 = 参数错误（完整日志在 build/pack-one-*.log）"
}

fn gui(opts_from_cli: Opts) -> i32 {
    unsafe {
        FreeConsole();
        InitCommonControls();
        let hinst = GetModuleHandleW(std::ptr::null());
        let cls = wide("EratwPackOneWndClass");
        let wc = WNDCLASSW {
            style: 3,
            lpfnWndProc: Some(wndproc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: hinst,
            hIcon: 0,
            hCursor: LoadCursorW(0, 32512 as *const u16),
            hbrBackground: 16,
            lpszMenuName: std::ptr::null(),
            lpszClassName: cls.as_ptr(),
        };
        if RegisterClassW(&wc) == 0 {
            msgbox(0, "注册窗口类失败", "eratw 一键出包", MB_OK | MB_ICONWARNING);
            return 2;
        }
        let follow = !opts_from_cli.follow_gui.is_empty();
        let title_text = if follow {
            format!("eratw 一键出包 · 实时进度【{}】", opts_from_cli.follow_title)
        } else {
            "eratw 一键出包 v1.3 —— 选包型，点开始，别的都不用管".to_string()
        };
        let title = wide(&title_text);
        let hwnd = CreateWindowExW(
            0,
            cls.as_ptr(),
            title.as_ptr(),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            120,
            80,
            960,
            720,
            0,
            0,
            hinst,
            std::ptr::null(),
        );
        if hwnd == 0 {
            msgbox(0, "创建窗口失败", "eratw 一键出包", MB_OK | MB_ICONWARNING);
            return 2;
        }
        let font = GetStockObject(DEFAULT_GUI_FONT);
        make_ctrl(hwnd, "STATIC", "自动：拉代码 / 校准 4.981 语料 / 补端点令牌与签名章 / 解 Gradle 锁 / 兼容档编 erbc / 出包五步体检 / 归档 / 发版 / 装机", 0, 0, 20, 14, 900, 22, 0, font);
        make_ctrl(hwnd, "STATIC", "① 包型", 0, 0, 20, 46, 120, 20, 0, font);
        make_ctrl(hwnd, "BUTTON", "正式包（上传线上）", BS_AUTORADIOBUTTON, 0, 20, 70, 250, 26, ID_MODE_RELEASE, font);
        make_ctrl(hwnd, "BUTTON", "测试用正式包（不上传）", BS_AUTORADIOBUTTON, 0, 285, 70, 280, 26, ID_MODE_TEST, font);
        make_ctrl(hwnd, "BUTTON", "测试包（小包/最快）", BS_AUTORADIOBUTTON, 0, 580, 70, 260, 26, ID_MODE_DEV, font);
        make_ctrl(hwnd, "BUTTON", "同步 git（拉最新）", BS_AUTOCHECKBOX, 0, 20, 106, 210, 24, ID_CHK_GIT, font);
        make_ctrl(hwnd, "BUTTON", "出完装到手机", BS_AUTOCHECKBOX, 0, 240, 106, 200, 24, ID_CHK_INSTALL, font);
        make_ctrl(hwnd, "BUTTON", "强制重编 erbc", BS_AUTOCHECKBOX, 0, 450, 106, 200, 24, ID_CHK_FORCE_ERBC, font);
        make_ctrl(hwnd, "BUTTON", "正式包允许上传", BS_AUTOCHECKBOX, 0, 660, 106, 230, 24, ID_CHK_UPLOAD, font);
        make_ctrl(hwnd, "STATIC", "② 发布说明（正式包上传用）　　　　　　　　③ 版本号（留空 = 不抬号，填 0.2.45 自动 +1 code）", 0, 0, 20, 140, 880, 20, 0, font);
        make_ctrl(hwnd, "EDIT", "", ES_AUTOHSCROLL | WS_BORDER, WS_EX_CLIENTEDGE, 20, 164, 690, 26, ID_EDIT_NOTE, font);
        make_ctrl(hwnd, "EDIT", "", ES_AUTOHSCROLL | WS_BORDER, WS_EX_CLIENTEDGE, 730, 164, 190, 26, ID_EDIT_VERSION, font);
        make_ctrl(hwnd, "BUTTON", "▶ 开始", BS_PUSHBUTTON, 0, 20, 202, 160, 38, ID_START, font);
        make_ctrl(hwnd, "BUTTON", "取消", BS_PUSHBUTTON, 0, 190, 202, 120, 38, ID_CANCEL, font);
        make_ctrl(hwnd, "BUTTON", "打开归档目录", BS_PUSHBUTTON, 0, 320, 202, 150, 38, ID_OPEN, font);
        make_ctrl(hwnd, "STATIC", "就绪", 0, 0, 20, 252, 900, 22, ID_STAGE, font);
        make_ctrl(hwnd, "msctls_progress32", "", PBS_SMOOTH, 0, 20, 278, 900, 22, ID_PROGRESS, font);
        SendMessageW(GetDlgItem(hwnd, ID_PROGRESS), PBM_SETRANGE32, 0, 100);
        make_ctrl(hwnd, "STATIC", "③ 实时日志", 0, 0, 20, 310, 200, 20, 0, font);
        let log = make_ctrl(hwnd, "EDIT", "", ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL | WS_VSCROLL | WS_BORDER, WS_EX_CLIENTEDGE, 20, 334, 900, 300, ID_EDIT_LOG, font);
        SendMessageW(log, EM_LIMITTEXT, 1 << 20, 0);
        make_ctrl(hwnd, "STATIC", "就绪 · 选包型后点【开始】", 0, 0, 20, 642, 900, 20, ID_STATUS, font);

        set_check(hwnd, match opts_from_cli.mode { Mode::Release => ID_MODE_RELEASE, Mode::TestRelease => ID_MODE_TEST, Mode::Dev => ID_MODE_DEV }, true);
        set_check(hwnd, ID_CHK_GIT, opts_from_cli.sync_git);
        set_check(hwnd, ID_CHK_INSTALL, opts_from_cli.install);
        set_check(hwnd, ID_CHK_UPLOAD, opts_from_cli.upload);
        set_check(hwnd, ID_CHK_FORCE_ERBC, opts_from_cli.force_erbc);
        EnableWindow(GetDlgItem(hwnd, ID_CANCEL), 0);
        if follow {
            for id in [ID_START, ID_CANCEL, ID_MODE_RELEASE, ID_MODE_TEST, ID_MODE_DEV,
                       ID_CHK_GIT, ID_CHK_INSTALL, ID_CHK_FORCE_ERBC, ID_CHK_UPLOAD,
                       ID_EDIT_NOTE, ID_EDIT_VERSION] {
                EnableWindow(GetDlgItem(hwnd, id), 0);
            }
            let s = wide("观察窗：跟随 agent 日志实时刷新，跑完也不会自动关闭");
            SetWindowTextW(GetDlgItem(hwnd, ID_STAGE), s.as_ptr());
            let st = wide(&format!("日志：{}", opts_from_cli.follow_gui));
            SetWindowTextW(GetDlgItem(hwnd, ID_STATUS), st.as_ptr());
            *FOLLOW.lock().unwrap() = Some(FollowState {
                path: PathBuf::from(&opts_from_cli.follow_gui),
                offset: 0,
                pending: String::new(),
                done: false,
            });
            SetTimer(hwnd, TIMER_ID, 250, std::ptr::null());
        }
        ShowWindow(hwnd, SW_SHOWNORMAL);
        UpdateWindow(hwnd);
        let mut m: MSG = std::mem::zeroed();
        while GetMessageW(&mut m, 0, 0, 0) > 0 {
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
    }
    0
}

// 日志/结果 JSON 永远落在同一个真实目录。以前 --root 给错时这里退回 "."，
// 再拼上 "eratw-android/build"，会在 cwd（pack_one/）下凭空造出一棵假代码树；
// 更糟的是日志和 pack-one-last.json 会分居两地。现在一律按 exe 父链定位真实代码根。
fn log_dir(opts: Option<&Opts>) -> PathBuf {
    if let Some(o) = opts {
        if let Ok(root) = resolve_root(o) {
            return root.join("eratw-android").join("build");
        }
    }
    if let Ok(exe) = env::current_exe() {
        let mut p = exe.parent().map(|x| x.to_path_buf());
        while let Some(d) = p {
            if has_code_marker(&d) {
                return d.join("eratw-android").join("build");
            }
            p = d.parent().map(|x| x.to_path_buf());
        }
        if let Some(d) = exe.parent() {
            return d.join("build"); // 兜底：exe 同目录，绝不伪造 eratw-android/
        }
    }
    PathBuf::from("build")
}

fn make_log_path(opts: &Opts) -> PathBuf {
    let dir = log_dir(Some(opts));
    let _ = fs::create_dir_all(&dir);
    // 秒级重名会让两个并发 agent 写同一个日志；带上 pid 彻底隔离。
    let p = dir.join(format!("pack-one-{}-{}.log", now_stamp(), std::process::id()));
    let _ = File::create(&p);
    // 开跑即把上一次的成功结果作废。否则 --root 写错 / 早期预检失败时，
    // agent 读 pack-one-last.json 会拿到上一轮的 ok=true 与旧 APK，误判成功。
    write_pending_result(&p, opts);
    p
}

// 桌面观察窗是独立进程：DETACHED_PROCESS + bInheritHandles=FALSE，
// 既不占 agent 的控制台/管道（命令行立刻能返回），CLI 退出后窗口也仍在桌面。
fn spawn_follow_window(log: &Path, opts: &Opts) {
    let exe = match env::current_exe() { Ok(p) => p, Err(_) => return };
    let label = if opts.bump_version.is_empty() {
        format!("{} · 不抬号", opts.mode.short())
    } else {
        format!("{} · {}", opts.mode.short(), opts.bump_version)
    };
    let mut spawned = false;
    let cmdline = format!(
        "\"{}\" --follow-gui \"{}\" --title \"{}\"",
        exe.display(),
        log.display(),
        label.replace('"', " ")
    );
    unsafe {
        let mut si: STARTUPINFOW = std::mem::zeroed();
        si.cb = std::mem::size_of::<STARTUPINFOW>() as DWORD;
        si.dwFlags = 0x00000001; // STARTF_USESHOWWINDOW
        si.wShowWindow = 1;      // SW_SHOWNORMAL
        let mut pi: PROCESS_INFORMATION = std::mem::zeroed();
        // 先请求脱离终端的 Job Object：否则 CLI 进程一退出，孙进程会被 Job 连带杀掉，
        // 用户就看不到跑完的结果卡片。Job 不允许 breakaway 时回退普通 DETACHED
        // （出包期间窗口仍可见，只是命令行结束可能被终端回收）。
        let mut cmd_try = wide(&cmdline);
        let mut ok = CreateProcessW(
            std::ptr::null(),
            cmd_try.as_mut_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            0, // bInheritHandles = FALSE —— 不继承 stdout 管道写端
            0x00000008 | 0x00000200 | 0x01000000, // DETACHED | NEW_GROUP | BREAKAWAY_FROM_JOB
            std::ptr::null(),
            std::ptr::null(),
            &si,
            &mut pi,
        );
        if ok != 0 {
            spawned = true;
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
        } else {
            let mut cmd_try2 = wide(&cmdline);
            ok = CreateProcessW(
                std::ptr::null(),
                cmd_try2.as_mut_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                0,
                0x00000008 | 0x00000200,
                std::ptr::null(),
                std::ptr::null(),
                &si,
                &mut pi,
            );
            if ok != 0 {
                spawned = true;
                CloseHandle(pi.hThread);
                CloseHandle(pi.hProcess);
            } else {
                eprintln!("[观察窗] CreateProcessW 失败 err={} cmd={}", GetLastError(), cmdline);
            }
        }
    }
    if spawned {
        eprintln!("[观察窗] 桌面进度窗已启动，跟随 {}", log.display());
    } else {
        eprintln!("[观察窗] 未能启动进度窗（不影响出包，日志仍在落盘）");
    }
}

// 结果 JSON 与日志永远同目录；字段形状恒定，agent 不必判断「这次有没有写」。
fn result_json_path(log: &Path) -> PathBuf {
    log.parent().unwrap_or(Path::new(".")).join("pack-one-last.json")
}

fn json_esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', " ").replace('\r', " ")
}

// action 必须显式：--selftest 也会写 ok=true，但 apk 为空；没有这个字段，
// 下一个 agent 会把「预检通过」当成「包已出」，拿空路径去交付（v1.2 实跑踩到）。
fn action_of(opts: &Opts) -> &'static str {
    if opts.selftest { "selftest" }
    else if opts.publish_only { "publish-only" }
    else { "build" }
}

// 开跑/失败时的最小结果：字段与终态完全一致，只是大多为空。
// status: running | ok | failed —— 进程被强杀时也只会留下 running，绝不会冒充成功。
fn write_status_result(log: &Path, status: &str, mode: &str, action: &str, err: &str) {
    let e = json_esc;
    let body = format!(
        "{{\n  \"ok\": {},\n  \"status\": \"{}\",\n  \"action\": \"{}\",\n  \"mode\": \"{}\",\n  \"version\": \"\",\n  \"version_name\": \"\",\n  \"version_code\": 0,\n  \"head\": \"\",\n  \"apk\": \"\",\n  \"sha256\": \"\",\n  \"github\": \"\",\n  \"online_manifest_verified\": false,\n  \"installed\": false,\n  \"log\": \"{}\",\n  \"error\": \"{}\"\n}}\n",
        status == "ok", e(status), e(action), e(mode), e(&log.to_string_lossy()), e(err)
    );
    let _ = fs::write(result_json_path(log), body);
}

fn write_pending_result(log: &Path, opts: &Opts) {
    write_status_result(log, "running", opts.mode.slug(), action_of(opts), "出包尚未结束（进行中或进程被中断）");
}

fn write_failed_result(log: &Path, opts: &Opts, err: &str) {
    write_status_result(log, "failed", opts.mode.slug(), action_of(opts), err);
}

// 只有 wf 还没构造出来就失败（--root 非法 / 工具链缺失 / 抢锁失败）时，
// inner 来不及写终态，外层据 status 判断并补写，避免残留 running。
fn ensure_terminal_result(log: &Path, opts: &Opts, r: &Result<String, String>) {
    let p = result_json_path(log);
    let pending = fs::read_to_string(&p)
        .map(|s| s.contains("\"status\": \"running\""))
        .unwrap_or(true);
    if pending {
        match r {
            Ok(_) => write_status_result(log, "ok", opts.mode.slug(), action_of(opts), ""),
            Err(e) => write_failed_result(log, opts, e),
        }
    }
}

fn write_done_marker(log: &Path, r: &Result<String, String>) {
    let ok = r.is_ok();
    let summary = match r {
        Ok(s) => s.lines().take(6).collect::<Vec<_>>().join(" | "),
        Err(e) => format!("失败：{}", e.lines().next().unwrap_or("")),
    };
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(log) {
        let _ = writeln!(f, "===ERATW_PACK_DONE=== {}", if ok { "OK" } else { "FAIL" });
        let _ = writeln!(f, "===ERATW_PACK_SUMMARY=== {}", summary.replace('\r', " ").replace('\n', " "));
        let _ = writeln!(f, "===ERATW_PACK_RESULT_JSON=== pack-one-last.json");
        let _ = writeln!(f, "===ERATW_PACK_RESULT_PATH=== {}", result_json_path(log).display());
        if !ok {
            let _ = writeln!(f, "===ERATW_PACK_RESULT_STATUS=== failed");
        }
    }
}

// 给「下一个 agent」的机器可读结果：不用翻日志、不用猜文件名。
fn write_result_json(wf: &Wf, log: &Path, r: &Result<String, String>) {
    let esc = json_esc;
    let err = r.as_ref().err().map(|e| esc(e)).unwrap_or_default();
    let body = format!(
        "{{\n  \"ok\": {},\n  \"status\": \"{}\",\n  \"action\": \"{}\",\n  \"mode\": \"{}\",\n  \"version\": \"{}\",\n  \"version_name\": \"{}\",\n  \"version_code\": {},\n  \"head\": \"{}\",\n  \"apk\": \"{}\",\n  \"sha256\": \"{}\",\n  \"github\": \"{}\",\n  \"online_manifest_verified\": {},\n  \"installed\": {},\n  \"log\": \"{}\",\n  \"error\": \"{}\"\n}}\n",
        r.is_ok(), if r.is_ok() { "ok" } else { "failed" }, action_of(&wf.opts), wf.opts.mode.slug(),
        esc(&wf.version), esc(&wf.version_name), wf.version_code,
        esc(&wf.git7), esc(&wf.apk_path.to_string_lossy()), esc(&wf.apk_sha), esc(&wf.github_url),
        wf.online_ok, wf.install_ok, esc(&log.to_string_lossy()), err
    );
    let p = result_json_path(log);
    let _ = fs::write(&p, body);
    wf.em.line(&format!("机器可读结果 = {}", p.display()));
}

// 参数错误（exit 2）也必须留下 failed 结果：否则 agent 读 pack-one-last.json
// 会拿到上一轮 ok=true 的旧 APK，把「命令根本没跑」当成「出包成功」。
fn record_cli_error(err: &str) {
    let dir = log_dir(None);
    let _ = fs::create_dir_all(&dir);
    let log = dir.join(format!("pack-one-{}-{}.log", now_stamp(), std::process::id()));
    let _ = fs::write(&log, format!("参数错误：{}\n", err));
    write_status_result(&log, "failed", "", "cli-error", &format!("参数错误：{}", err));
    write_done_marker(&log, &Err::<String, String>(format!("参数错误：{}", err)));
    eprintln!("机器可读结果 = {}", result_json_path(&log).display());
    eprintln!("日志 = {}", log.display());
}

fn main() {
    let opts = match parse_cli() {
        Ok(o) => o,
        Err(e) => {
            eprintln!("参数错误：{}\n\n{}", e, help_text());
            record_cli_error(&e);
            std::process::exit(2);
        }
    };
    if !opts.follow_gui.is_empty() {
        std::process::exit(gui(opts));
    }
    if opts.headless {
        let log_path = make_log_path(&opts);
        if !opts.no_window {
            spawn_follow_window(&log_path, &opts);
            thread::sleep(Duration::from_millis(300));
        }
        let r = build_and_run(0, opts, log_path.clone());
        match r {
            Ok(s) => { println!("{}", s); std::process::exit(0); }
            Err(e) => { eprintln!("❌ {}", e); std::process::exit(1); }
        }
    } else {
        std::process::exit(gui(opts));
    }
}
