//! Runs a command in a new pty and copies its output to stdout without kitty graphics commands.
//! Usage: herdr-attach <command> [args...]

mod filter;

use filter::GraphicsFilter;
use std::ffi::CString;
use std::io::{self, Write};
use std::os::fd::RawFd;
use std::process::exit;
use std::ptr;
use std::sync::atomic::{AtomicI32, Ordering};
use std::thread;

const HOLD_MS: i32 = 20;

static WINCH_PIPE: AtomicI32 = AtomicI32::new(-1);

extern "C" fn on_winch(_: libc::c_int) {
    let fd = WINCH_PIPE.load(Ordering::Relaxed);
    if fd >= 0 {
        unsafe { libc::write(fd, [0u8].as_ptr().cast(), 1) };
    }
}

fn fail(what: &str) -> ! {
    eprintln!("herdr-attach: {what}: {}", io::Error::last_os_error());
    exit(127);
}

fn window_size(fd: RawFd) -> Option<libc::winsize> {
    let mut ws: libc::winsize = unsafe { std::mem::zeroed() };
    (unsafe { libc::ioctl(fd, libc::TIOCGWINSZ, &mut ws) } == 0).then_some(ws)
}

fn write_all(fd: RawFd, mut buf: &[u8]) -> bool {
    while !buf.is_empty() {
        let n = unsafe { libc::write(fd, buf.as_ptr().cast(), buf.len()) };
        if n < 0 {
            if io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return false;
        }
        buf = &buf[n as usize..];
    }
    true
}

fn spawn(argv: &[CString], master: &mut RawFd) -> libc::pid_t {
    let mut ws = window_size(libc::STDIN_FILENO);
    let ws_ptr = ws.as_mut().map_or(ptr::null_mut(), |w| w as *mut libc::winsize);
    let pid = unsafe { libc::forkpty(master, ptr::null_mut(), ptr::null_mut(), ws_ptr.cast()) };
    if pid < 0 {
        fail("forkpty");
    }
    if pid == 0 {
        let mut ptrs: Vec<*const libc::c_char> = argv.iter().map(|a| a.as_ptr()).collect();
        ptrs.push(ptr::null());
        unsafe { libc::execvp(ptrs[0], ptrs.as_ptr()) };
        fail("exec");
    }
    pid
}

fn main() {
    let argv: Vec<CString> = std::env::args()
        .skip(1)
        .map(|a| CString::new(a).unwrap_or_else(|_| fail("argument")))
        .collect();
    if argv.is_empty() {
        eprintln!("usage: herdr-attach <command> [args...]");
        exit(2);
    }

    let mut master: RawFd = -1;
    let pid = spawn(&argv, &mut master);

    let saved = unsafe {
        let mut t: libc::termios = std::mem::zeroed();
        (libc::tcgetattr(libc::STDIN_FILENO, &mut t) == 0).then(|| {
            let mut raw = t;
            libc::cfmakeraw(&mut raw);
            libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &raw);
            t
        })
    };

    let mut fds = [0 as RawFd; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        fail("pipe");
    }
    WINCH_PIPE.store(fds[1], Ordering::Relaxed);
    unsafe { libc::signal(libc::SIGWINCH, on_winch as *const () as libc::sighandler_t) };
    let winch_read = fds[0];
    thread::spawn(move || {
        let mut b = [0u8; 64];
        while unsafe { libc::read(winch_read, b.as_mut_ptr().cast(), b.len()) } > 0 {
            if let Some(ws) = window_size(libc::STDIN_FILENO) {
                unsafe { libc::ioctl(master, libc::TIOCSWINSZ, &ws) };
            }
        }
    });

    thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            let n = unsafe { libc::read(libc::STDIN_FILENO, buf.as_mut_ptr().cast(), buf.len()) };
            if n < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if n <= 0 || !write_all(master, &buf[..n as usize]) {
                break;
            }
        }
    });

    let mut filter = GraphicsFilter::new();
    let mut buf = [0u8; 65536];
    let mut out = Vec::with_capacity(buf.len());
    loop {
        let timeout = if filter.holding() { HOLD_MS } else { -1 };
        let mut pfd = libc::pollfd { fd: master, events: libc::POLLIN, revents: 0 };
        let ready = unsafe { libc::poll(&mut pfd, 1, timeout) };
        if ready < 0 {
            if io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }
        out.clear();
        if ready == 0 {
            filter.flush(&mut out);
        } else {
            let n = unsafe { libc::read(master, buf.as_mut_ptr().cast(), buf.len()) };
            if n < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if n <= 0 {
                break;
            }
            filter.push(&buf[..n as usize], &mut out);
        }
        if !out.is_empty() && !write_all(libc::STDOUT_FILENO, &out) {
            break;
        }
    }
    out.clear();
    filter.flush(&mut out);
    write_all(libc::STDOUT_FILENO, &out);
    let _ = io::stdout().flush();

    let mut status = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    if let Some(t) = saved {
        unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &t) };
    }
    let code = if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        128 + libc::WTERMSIG(status)
    } else {
        1
    };
    exit(code);
}
