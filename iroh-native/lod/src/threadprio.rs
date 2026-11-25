// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! Calling-thread scheduling priority for the LOD worker threads (Java thread priorities are no-ops on Linux).
//! No dependencies: libc / kernel32 symbols are declared directly. Levels: 0 normal, 1 low, 2 idle.

/// Returns 0 on success, a negative value when the platform refuses or has no support (callers ignore it).
pub fn set(level: i32) -> i32 {
    imp::set(level)
}

#[cfg(target_os = "linux")]
mod imp {
    #[repr(C)]
    struct SchedParam {
        priority: i32,
    }
    extern "C" {
        fn setpriority(which: i32, who: u32, prio: i32) -> i32;
        fn sched_setscheduler(pid: i32, policy: i32, param: *const SchedParam) -> i32;
        fn syscall(num: i64, ...) -> i64;
    }
    const SYS_GETTID: i64 = if cfg!(target_arch = "x86_64") { 186 } else { 178 }; // x86_64, aarch64

    pub fn set(level: i32) -> i32 {
        let tid = unsafe { syscall(SYS_GETTID) } as i32;
        if tid <= 0 {
            return -1;
        }
        let p = SchedParam { priority: 0 };
        unsafe {
            match level {
                2 => {
                    // SCHED_IDLE runs only when a core would otherwise idle
                    if sched_setscheduler(tid, 5, &p) != 0 {
                        return -2;
                    }
                    0
                }
                1 => (setpriority(0, tid as u32, 10) != 0) as i32 * -3,
                _ => {
                    let a = sched_setscheduler(tid, 0, &p);
                    let b = setpriority(0, tid as u32, 0);
                    if a != 0 || b != 0 { -4 } else { 0 }
                }
            }
        }
    }
}

#[cfg(windows)]
mod imp {
    extern "system" {
        fn GetCurrentThread() -> isize;
        fn SetThreadPriority(thread: isize, prio: i32) -> i32;
    }
    pub fn set(level: i32) -> i32 {
        let p = match level {
            2 => -15, // THREAD_PRIORITY_IDLE
            1 => -1,  // THREAD_PRIORITY_BELOW_NORMAL
            _ => 0,
        };
        if unsafe { SetThreadPriority(GetCurrentThread(), p) } != 0 { 0 } else { -1 }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    pub fn set(_level: i32) -> i32 {
        -1
    }
}
