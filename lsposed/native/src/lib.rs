mod generated;

use std::ffi::CStr;
use std::io::ErrorKind;

use crate::generated::iface_lists::matches_vpn;

uniffi::setup_scaffolding!();

// ── Probe outcome types — crossing the FFI ───────────────────────────

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckStatus {
    /// Probe ran and saw nothing VPN-shaped, or was legitimately blocked
    /// (SELinux denial, ENODEV, etc.) — both outcomes confirm the VPN is
    /// hidden from this surface.
    Pass,
    /// Probe surfaced VPN-shaped data the kmod / lsposed should have hidden.
    Fail,
    /// App has no network permission, so the probe couldn't run at all.
    /// Reported separately from Pass/Fail so the UI can tell the user to
    /// enable network access before trusting the results.
    NetworkBlocked,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct CheckOutput {
    pub status: CheckStatus,
    pub detail: String,
}

impl CheckOutput {
    fn pass(detail: impl Into<String>) -> Self {
        Self {
            status: CheckStatus::Pass,
            detail: detail.into(),
        }
    }

    fn fail(detail: impl Into<String>) -> Self {
        Self {
            status: CheckStatus::Fail,
            detail: detail.into(),
        }
    }

    fn network_blocked(detail: impl Into<String>) -> Self {
        Self {
            status: CheckStatus::NetworkBlocked,
            detail: detail.into(),
        }
    }
}

fn is_vpn_iface(name: &str) -> bool {
    matches_vpn(name.as_bytes())
}

fn clean_iface_name(word: &str) -> String {
    word.trim_matches(|c: char| !c.is_alphanumeric() && c != '-' && c != '_')
        .to_string()
}

fn is_interface_up_with_fd(fd: libc::c_int, name: &str) -> bool {
    if name.is_empty() {
        return false;
    }
    unsafe {
        let mut ifr: libc::ifreq = std::mem::zeroed();
        let name_bytes = name.as_bytes();
        let optlen = std::cmp::min(name_bytes.len(), ifr.ifr_name.len() - 1);
        std::ptr::copy_nonoverlapping(
            name_bytes.as_ptr(),
            ifr.ifr_name.as_mut_ptr().cast(),
            optlen,
        );
        if libc::ioctl(fd, libc::SIOCGIFFLAGS as _, &ifr) < 0 {
            let err = last_os_errno();
            err == libc::ENODEV || err == libc::ENXIO
        } else {
            let flags = ifr.ifr_ifru.ifru_flags as u32;
            (flags & libc::IFF_UP as u32) != 0
        }
    }
}

fn is_interface_up(name: &str) -> bool {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return false;
        }
        let up = is_interface_up_with_fd(fd, name);
        libc::close(fd);
        up
    }
}

fn is_selinux_denial(e: &std::io::Error) -> bool {
    e.kind() == ErrorKind::PermissionDenied
}

// ── helpers ──────────────────────────────────────────────────────────

fn cstr_to_str(ptr: *const libc::c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned()
}

fn last_os_error() -> String {
    std::io::Error::last_os_error().to_string()
}

fn last_os_errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

fn join_list(v: &[String]) -> String {
    v.join(", ")
}

fn format_iface_result(all: &[String], vpn: &[String], context: &str) -> CheckOutput {
    if vpn.is_empty() {
        CheckOutput::pass(format!("{context} [{list}], no VPN", list = join_list(all)))
    } else {
        CheckOutput::fail(format!(
            "VPN interfaces [{vpn}] in [{all}]",
            vpn = join_list(vpn),
            all = join_list(all),
        ))
    }
}

fn path_exists_oracle(path: &str) -> bool {
    unsafe {
        let c_path = match std::ffi::CString::new(path) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let ret = libc::access(c_path.as_ptr(), libc::F_OK);
        if ret == 0 {
            true
        } else {
            let err = last_os_errno();
            err != libc::ENOENT
        }
    }
}

fn check_path_via_oracle(base_dir: &str, iface: &str) -> Option<bool> {
    let nonexistent_path = format!("{base_dir}/nonexistent_iface_probe_123");
    if path_exists_oracle(&nonexistent_path) {
        None
    } else {
        let actual_path = format!("{base_dir}/{iface}");
        Some(path_exists_oracle(&actual_path))
    }
}

// ── structs missing from libc crate on Android ───────────────────────

#[repr(C)]
struct Ifinfomsg {
    ifi_family: u8,
    _pad: u8,
    ifi_type: u16,
    ifi_index: i32,
    ifi_flags: u32,
    ifi_change: u32,
}

#[repr(C)]
struct Rtmsg {
    rtm_family: u8,
    rtm_dst_len: u8,
    rtm_src_len: u8,
    rtm_tos: u8,
    rtm_table: u8,
    rtm_protocol: u8,
    rtm_scope: u8,
    rtm_type: u8,
    rtm_flags: u32,
}

#[repr(C)]
struct Rtattr {
    rta_len: u16,
    rta_type: u16,
}

#[repr(C)]
struct FibRuleHdr {
    family: u8,
    dst_len: u8,
    src_len: u8,
    tos: u8,
    table: u8,
    res1: u8,
    res2: u8,
    action: u8,
    flags: u32,
}

#[repr(C)]
struct Ndmsg {
    ndm_family: u8,
    ndm_pad1: u8,
    ndm_pad2: u16,
    ndm_ifindex: i32,
    ndm_state: u16,
    ndm_flags: u8,
    ndm_type: u8,
}

const IFLA_IFNAME: u16 = 3;
const RTA_OIF: u16 = 4;

// Traffic control (qdisc) constants — not in libc for Android
const RTM_NEWQDISC: u16 = 36;
const RTM_GETQDISC: u16 = 38;
const TCA_KIND: u16 = 1;

#[repr(C)]
struct Tcmsg {
    tcm_family: u8,
    _pad1: u8,
    _pad2: u16,
    tcm_ifindex: i32,
    tcm_handle: u32,
    tcm_parent: u32,
    tcm_info: u32,
}

// TCP_INFO partial layout — bytes 0-23 cover the MSS fields we need.
// Stable across all GKI versions: 8 flag bytes + rto + ato + snd_mss + rcv_mss.
const TCP_INFO: libc::c_int = 11;

#[repr(C)]
struct TcpInfoMss {
    _pre: [u8; 8], // state, ca_state, retransmits, probes, backoff, options, wscale, delivery_rate
    _rto: u32,     // tcpi_rto
    _ato: u32,     // tcpi_ato
    tcpi_snd_mss: u32,
    tcpi_rcv_mss: u32,
}

// SO_TIMESTAMPING constants
const SO_TIMESTAMPING: libc::c_int = 37;
const SOF_TIMESTAMPING_TX_HARDWARE: u32 = 1 << 0;
const SOF_TIMESTAMPING_TX_SOFTWARE: u32 = 1 << 1;
const SOF_TIMESTAMPING_RAW_HARDWARE: u32 = 1 << 6;
const SOF_TIMESTAMPING_OPT_TSONLY: u32 = 1 << 11;
const SCM_TIMESTAMPING: libc::c_int = 37;

// scm_timestamping: 3 x timespec64 (sec: i64, nsec: i64)
// ts[0]=SW ts, ts[1]=legacy HW ts, ts[2]=RAW HW ts
#[repr(C)]
struct ScmTimestamping {
    ts: [[i64; 2]; 3],
}

// ── check implementations ────────────────────────────────────────────

/// Open an IPv4 datagram socket and pass it to `f`, then close it.
/// Returns `CheckOutput::network_blocked(...)` if `socket()` returns
/// ECONNREFUSED (no NETWORK permission), `CheckOutput::fail(...)` for
/// any other socket() failure, otherwise the result of `f(fd)`.
unsafe fn with_inet_dgram_socket(f: impl FnOnce(libc::c_int) -> CheckOutput) -> CheckOutput {
    let fd = unsafe { libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0) };
    if fd < 0 {
        let err = last_os_errno();
        if err == libc::ECONNREFUSED {
            return CheckOutput::network_blocked(
                "socket() returned ECONNREFUSED — network access disabled for this app",
            );
        }
        return CheckOutput::fail(format!("cannot create socket: {}", last_os_error()));
    }
    let out = f(fd);
    unsafe { libc::close(fd) };
    out
}

#[uniffi::export]
fn check_ioctl_siocgifflags() -> CheckOutput {
    unsafe {
        with_inet_dgram_socket(|fd| {
            let mut ifr: libc::ifreq = std::mem::zeroed();
            let name = b"tun0\0";
            ifr.ifr_name[..name.len()].copy_from_slice(&name.map(|b| b as libc::c_char));

            let ret = libc::ioctl(fd, libc::SIOCGIFFLAGS as _, &ifr);
            let err = last_os_errno();

            if ret < 0 {
                if err == libc::ENODEV {
                    CheckOutput::pass(
                        "ioctl(tun0, SIOCGIFFLAGS) returned ENODEV — interface not visible",
                    )
                } else if err == libc::ENXIO {
                    CheckOutput::pass(
                        "ioctl(tun0, SIOCGIFFLAGS) returned ENXIO — interface not visible",
                    )
                } else {
                    CheckOutput::fail(format!("ioctl returned error {err} ({})", last_os_error()))
                }
            } else {
                let flags = ifr.ifr_ifru.ifru_flags as u32;
                if flags & libc::IFF_UP as u32 == 0 {
                    CheckOutput::pass(format!(
                        "ioctl(tun0, SIOCGIFFLAGS) returned flags=0x{flags:x} — interface is DOWN/inactive",
                    ))
                } else {
                    CheckOutput::fail(format!(
                        "tun0 is visible! flags=0x{flags:x} (IFF_UP={}, IFF_RUNNING={})",
                        u8::from(flags & libc::IFF_UP as u32 != 0),
                        u8::from(flags & libc::IFF_RUNNING as u32 != 0),
                    ))
                }
            }
        })
    }
}

#[uniffi::export]
fn check_ioctl_siocgifmtu() -> CheckOutput {
    unsafe {
        with_inet_dgram_socket(|fd| {
            let mut ifr: libc::ifreq = std::mem::zeroed();
            let name = b"tun0\0";
            ifr.ifr_name[..name.len()].copy_from_slice(&name.map(|b| b as libc::c_char));

            let ret = libc::ioctl(fd, libc::SIOCGIFMTU as _, &ifr);
            let err = last_os_errno();

            if ret < 0 {
                if err == libc::ENODEV || err == libc::ENXIO {
                    CheckOutput::pass(
                        "ioctl(tun0, SIOCGIFMTU) returned ENODEV — interface not visible",
                    )
                } else {
                    CheckOutput::fail(format!("ioctl returned error {err} ({})", last_os_error()))
                }
            } else {
                // Check if UP using SIOCGIFFLAGS
                let mut flags_ifr: libc::ifreq = std::mem::zeroed();
                flags_ifr.ifr_name[..name.len()].copy_from_slice(&name.map(|b| b as libc::c_char));
                if libc::ioctl(fd, libc::SIOCGIFFLAGS as _, &mut flags_ifr) == 0 {
                    let flags = flags_ifr.ifr_ifru.ifru_flags as u32;
                    if flags & libc::IFF_UP as u32 == 0 {
                        return CheckOutput::pass(
                            "ioctl(tun0, SIOCGIFMTU) succeeded, but interface is DOWN/inactive",
                        );
                    }
                }
                let mtu = ifr.ifr_ifru.ifru_mtu;
                CheckOutput::fail(format!("tun0 is visible! MTU={mtu}"))
            }
        })
    }
}

#[uniffi::export]
fn check_ioctl_siocgifconf() -> CheckOutput {
    unsafe {
        with_inet_dgram_socket(|fd| {
            let mut buf = [0u8; 4096];
            let mut ifc: libc::ifconf = std::mem::zeroed();
            ifc.ifc_len = buf.len() as libc::c_int;
            ifc.ifc_ifcu.ifcu_buf = buf.as_mut_ptr().cast();

            if libc::ioctl(fd, libc::SIOCGIFCONF as _, &mut ifc) < 0 {
                let e = last_os_error();
                return CheckOutput::fail(format!("ioctl error: {e}"));
            }

            let count = ifc.ifc_len as usize / std::mem::size_of::<libc::ifreq>();
            let reqs = std::slice::from_raw_parts(buf.as_ptr() as *const libc::ifreq, count);

            let mut all = Vec::new();
            let mut vpn = Vec::new();
            for req in reqs {
                let name = cstr_to_str(req.ifr_name.as_ptr());
                if is_vpn_iface(&name) && is_interface_up_with_fd(fd, &name) {
                    vpn.push(name.clone());
                }
                all.push(name);
            }

            format_iface_result(&all, &vpn, &format!("{count} interfaces visible:"))
        })
    }
}

#[uniffi::export]
fn check_getifaddrs() -> CheckOutput {
    unsafe {
        let mut addrs: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut addrs) != 0 {
            return CheckOutput::fail(format!("getifaddrs error: {}", last_os_error()));
        }

        let mut all: Vec<String> = Vec::new();
        let mut vpn: Vec<String> = Vec::new();
        let mut ifa = addrs;
        while !ifa.is_null() {
            let entry = &*ifa;
            if !entry.ifa_name.is_null() {
                let name = cstr_to_str(entry.ifa_name);
                if !all.contains(&name) {
                    all.push(name.clone());
                }
                if is_vpn_iface(&name) && !vpn.contains(&name) {
                    let is_up = (entry.ifa_flags as u32 & libc::IFF_UP as u32) != 0;
                    if is_up {
                        vpn.push(name);
                    }
                }
            }
            ifa = entry.ifa_next;
        }
        libc::freeifaddrs(addrs);

        format_iface_result(&all, &vpn, &format!("{} unique interfaces:", all.len()))
    }
}

fn check_proc_file(path: &str) -> CheckOutput {
    match std::fs::read_to_string(path) {
        Err(e) => {
            if is_selinux_denial(&e) {
                return CheckOutput::pass(format!(
                    "access denied by SELinux ({e}) — app cannot read {path}"
                ));
            }
            CheckOutput::fail(format!("cannot open {path}: {e}"))
        }
        Ok(content) => {
            let mut total = 0;
            let mut vpn_lines = Vec::new();
            for line in content.lines() {
                if line.is_empty() {
                    continue;
                }
                total += 1;
                let has_active_vpn = line.split_ascii_whitespace().any(|word| {
                    let cleaned = clean_iface_name(word);
                    is_vpn_iface(&cleaned) && is_interface_up(&cleaned)
                });
                if has_active_vpn {
                    vpn_lines.push(line[..line.len().min(80)].to_string());
                }
            }
            if vpn_lines.is_empty() {
                CheckOutput::pass(format!("{total} lines in {path}, no VPN entries"))
            } else {
                let details: String = vpn_lines.iter().map(|l| format!("\n  {l}")).collect();
                CheckOutput::fail(format!("{} VPN lines in {path}:{details}", vpn_lines.len()))
            }
        }
    }
}

/// Upper bound on recvmsg iterations for a single netlink dump. A real
/// RTM_GETLINK / RTM_GETROUTE dump completes in a few 32 KiB reads; a stream
/// that exceeds this is looping (a kernel iface filter re-sending without
/// NLMSG_DONE — issue #61), so we stop instead of growing the result `Vec`
/// without bound until Scudo aborts the process with an OOM map failure.
const MAX_NETLINK_RECV_ITERS: usize = 256;

/// Wrapper around recvmsg for netlink sockets. Uses recvmsg (not recv/recvfrom)
/// so that the recvmsg hook can filter the response.
unsafe fn netlink_recv(fd: i32, buf: &mut [u8]) -> isize {
    unsafe {
        let mut iov = libc::iovec {
            iov_base: buf.as_mut_ptr().cast(),
            iov_len: buf.len(),
        };
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        libc::recvmsg(fd, &mut msg, 0)
    }
}

/// Open a bound NETLINK_ROUTE socket.
///
/// `Err` is short-circuit control flow: callers `return` it as the probe
/// outcome verbatim. The wrapped `CheckOutput` may carry any status —
/// SELinux denials map to `Pass` (the kernel hid the interface, exactly
/// what we want), real failures map to `Fail`.
fn open_netlink() -> Result<i32, CheckOutput> {
    unsafe {
        let fd = libc::socket(
            libc::AF_NETLINK,
            libc::SOCK_RAW | libc::SOCK_CLOEXEC,
            libc::NETLINK_ROUTE,
        );
        if fd < 0 {
            let e = std::io::Error::last_os_error();
            return Err(if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink socket denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("cannot create netlink socket: {e}"))
            });
        }

        // Receive timeout: a kernel-side interface filter can, on some kernels,
        // re-send a dump without ever emitting NLMSG_DONE (issue #61, observed
        // on android14-6.1). Without a timeout the next blocking recvmsg hangs
        // the diagnostics thread forever; with it the read returns EAGAIN and
        // the loop exits. Best-effort — the per-call iteration cap is the hard
        // backstop, so a setsockopt failure is non-fatal.
        let tv = libc::timeval {
            tv_sec: 2,
            tv_usec: 0,
        };
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            std::ptr::from_ref(&tv).cast(),
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        );

        Ok(fd)
    }
}

/// Parse netlink messages from a buffer, calling `on_msg` for each message.
/// Returns false if NLMSG_DONE or NLMSG_ERROR was seen.
///
/// # Safety
/// `buf` must contain valid netlink messages up to `len` bytes.
unsafe fn parse_netlink_msgs(
    buf: &[u8],
    len: usize,
    msg_type: u16,
    mut on_msg: impl FnMut(&[u8], usize, usize),
) -> bool {
    let mut offset = 0usize;
    let hdr_size = std::mem::size_of::<libc::nlmsghdr>();
    while offset + hdr_size <= len {
        let nh = unsafe { &*(buf.as_ptr().add(offset) as *const libc::nlmsghdr) };
        let msg_len = nh.nlmsg_len as usize;
        if msg_len < hdr_size || msg_len > len - offset {
            break;
        }
        if nh.nlmsg_type == libc::NLMSG_DONE as u16 || nh.nlmsg_type == libc::NLMSG_ERROR as u16 {
            return false;
        }
        if nh.nlmsg_type == msg_type {
            on_msg(buf, offset, msg_len);
        }
        offset += (msg_len + 3) & !3;
    }
    true // continue receiving
}

/// Iterate rtattr entries within a netlink message payload.
///
/// # Safety
/// `buf[start..end]` must contain valid rtattr entries.
unsafe fn for_each_rtattr(
    buf: &[u8],
    start: usize,
    end: usize,
    mut on_attr: impl FnMut(&Rtattr, &[u8]),
) {
    // Walk rtattrs in `buf[start..end]`. For each, hand the callback
    // the header AND a slice covering its payload — already bounds-
    // checked against `end`, so callbacks can never read past the
    // message. A truncated tail (rta_len < 4, or rta_len reaching
    // past `end`) ends the walk; netlink dumps end on padding, so
    // this is the normal exit too.
    let mut off = start;
    while off + 4 <= end {
        let rta = unsafe { &*(buf.as_ptr().add(off) as *const Rtattr) };
        let rta_len = rta.rta_len as usize;
        if rta_len < 4 || off + rta_len > end {
            break;
        }
        on_attr(rta, &buf[off + 4..off + rta_len]);
        off += (rta_len + 3) & !3;
    }
}

#[uniffi::export]
pub fn check_netlink_getlink() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            ifm: Ifinfomsg,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETLINK;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink RTM_GETLINK denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut all = Vec::new();
        let mut vpn = Vec::new();
        let hdr_plus_ifinfo =
            std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<Ifinfomsg>();

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWLINK,
                |b, offset, msg_len| {
                    let data_start = offset + hdr_plus_ifinfo;
                    let msg_end = offset + msg_len;
                    let ifi_ptr = b
                        .as_ptr()
                        .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                        as *const Ifinfomsg;
                    let ifi = &*ifi_ptr;
                    let is_up = (ifi.ifi_flags & libc::IFF_UP as u32) != 0;

                    for_each_rtattr(b, data_start, msg_end, |rta, payload| {
                        if rta.rta_type == IFLA_IFNAME && !payload.is_empty() {
                            // IFLA_IFNAME is a NUL-terminated string;
                            // payload was bounds-checked by for_each_rtattr.
                            let name = cstr_to_str(payload.as_ptr() as *const libc::c_char);
                            if is_vpn_iface(&name) && is_up {
                                vpn.push(name.clone());
                            }
                            all.push(name);
                        }
                    });
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        format_iface_result(
            &all,
            &vpn,
            &format!("{} interfaces via netlink:", all.len()),
        )
    }
}

#[uniffi::export]
fn check_netlink_getroute() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            rtm: Rtmsg,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETROUTE;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink RTM_GETROUTE denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut vpn = Vec::new();
        let mut total = 0u32;
        let hdr_plus_rtmsg = std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<Rtmsg>();

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWROUTE,
                |b, offset, msg_len| {
                    total += 1;
                    let data_start = offset + hdr_plus_rtmsg;
                    let msg_end = offset + msg_len;
                    for_each_rtattr(b, data_start, msg_end, |rta, payload| {
                        if rta.rta_type == RTA_OIF && payload.len() >= 4 {
                            let ifindex = i32::from_ne_bytes(payload[..4].try_into().unwrap());
                            let mut ifname_buf = [0u8; libc::IF_NAMESIZE];
                            let ptr = libc::if_indextoname(
                                ifindex as u32,
                                ifname_buf.as_mut_ptr().cast(),
                            );
                            if !ptr.is_null() {
                                let name = cstr_to_str(ptr);
                                if is_vpn_iface(&name) && is_interface_up(&name) {
                                    vpn.push(name);
                                }
                            }
                        }
                    });
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        if vpn.is_empty() {
            CheckOutput::pass(format!("{total} routes, no VPN"))
        } else {
            CheckOutput::fail(format!("VPN routes via [{}]", join_list(&vpn)))
        }
    }
}

#[uniffi::export]
fn check_netlink_anonymous_route() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            rtm: Rtmsg,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETROUTE;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!(
                    "netlink RTM_GETROUTE (anon) denied by SELinux ({e})"
                ))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut anon_indices = Vec::new();
        let mut total = 0u32;
        let hdr_plus_rtmsg = std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<Rtmsg>();

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWROUTE,
                |b, offset, msg_len| {
                    total += 1;
                    let data_start = offset + hdr_plus_rtmsg;
                    let msg_end = offset + msg_len;
                    for_each_rtattr(b, data_start, msg_end, |rta, payload| {
                        if rta.rta_type == RTA_OIF && payload.len() >= 4 {
                            let ifindex = i32::from_ne_bytes(payload[..4].try_into().unwrap());
                            if ifindex > 1 {
                                let mut ifname_buf = [0u8; libc::IF_NAMESIZE];
                                let ptr = libc::if_indextoname(
                                    ifindex as u32,
                                    ifname_buf.as_mut_ptr().cast(),
                                );
                                if ptr.is_null() && !anon_indices.contains(&ifindex) {
                                    anon_indices.push(ifindex);
                                }
                            }
                        }
                    });
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        if anon_indices.is_empty() {
            CheckOutput::pass(format!("{total} routes, no anonymous interfaces"))
        } else {
            CheckOutput::fail(format!(
                "Anonymous routes found via ifindices [{}] (interface names hidden, but routes leaked!)",
                anon_indices
                    .iter()
                    .map(|i| i.to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            ))
        }
    }
}

#[uniffi::export]
fn check_sys_class_net() -> CheckOutput {
    let sysfs_bases = ["/sys/class/net", "/sys/devices/virtual/net"];
    let test_ifaces = ["tun0", "tun1", "wg0", "wg1", "ppp0", "ppp1"];

    let mut path_results = Vec::new();
    let mut any_failed = false;
    let mut leaked_interfaces = Vec::new();

    for &base in &sysfs_bases {
        let mut leaked_here = Vec::new();
        let mut readdir_denied = false;

        // 1. Try readdir
        match std::fs::read_dir(base) {
            Err(_) => {
                readdir_denied = true;
            }
            Ok(entries) => {
                for entry in entries.flatten() {
                    let name = entry.file_name().to_string_lossy().into_owned();
                    if is_vpn_iface(&name) && is_interface_up(&name) {
                        leaked_here.push(name);
                    }
                }
            }
        }

        // 2. Try Path Existence Oracle
        let mut oracle_denied = false;
        for &iface in &test_ifaces {
            match check_path_via_oracle(base, iface) {
                Some(true) => {
                    if is_interface_up(iface) && !leaked_here.contains(&iface.to_string()) {
                        leaked_here.push(iface.to_string());
                    }
                }
                Some(false) => {}
                None => {
                    oracle_denied = true;
                }
            }
        }

        // Format result for this path
        if !leaked_here.is_empty() {
            any_failed = true;
            for iface in &leaked_here {
                if !leaked_interfaces.contains(iface) {
                    leaked_interfaces.push(iface.clone());
                }
            }
            path_results.push(format!("{base}: НЕ ОК (leaked {})", leaked_here.join(",")));
        } else if readdir_denied && oracle_denied {
            path_results.push(format!("{base}: OK (blocked)"));
        } else {
            path_results.push(format!("{base}: OK"));
        }
    }

    let details = path_results.join(", ");

    if any_failed {
        CheckOutput::fail(format!(
            "VPN leaked: [{}] — Details: {}",
            leaked_interfaces.join(", "),
            details
        ))
    } else {
        CheckOutput::pass(format!("All paths secure — Details: {}", details))
    }
}

// ── /proc/net/* wrappers: one uniffi export per path so the Kotlin side
//    keeps a thin `checkProcNetFoo(): CheckOutput` surface instead of
//    pushing path strings across the FFI. ──────────────────────────────

#[uniffi::export]
fn check_proc_net_route() -> CheckOutput {
    check_proc_file("/proc/net/route")
}

#[uniffi::export]
fn check_proc_net_if_inet6() -> CheckOutput {
    check_proc_file("/proc/net/if_inet6")
}

#[uniffi::export]
fn check_proc_net_ipv6_route() -> CheckOutput {
    check_proc_file("/proc/net/ipv6_route")
}

#[uniffi::export]
fn check_proc_net_tcp() -> CheckOutput {
    check_proc_file("/proc/net/tcp")
}

#[uniffi::export]
fn check_proc_net_tcp6() -> CheckOutput {
    check_proc_file("/proc/net/tcp6")
}

#[uniffi::export]
fn check_proc_net_udp() -> CheckOutput {
    check_proc_file("/proc/net/udp")
}

#[uniffi::export]
fn check_proc_net_udp6() -> CheckOutput {
    check_proc_file("/proc/net/udp6")
}

#[uniffi::export]
fn check_proc_net_dev() -> CheckOutput {
    check_proc_file("/proc/net/dev")
}

/// Parse /proc/net/dev and return raw per-interface TX/RX byte counters as CSV.
///
/// Format: one line per interface, "ifname,tx_bytes,rx_bytes".
/// Called from Kotlin to get ground-truth stats bypassing Java SELinux restrictions.
/// Returns an empty string if the file is unreadable (SELinux denial or not available).
#[uniffi::export]
fn parse_proc_net_dev_csv() -> String {
    let content = match std::fs::read_to_string("/proc/net/dev") {
        Ok(s) => s,
        Err(_) => return String::new(),
    };
    let mut out = String::new();
    for line in content.lines().skip(2) {
        // Format: "  iface: rx_bytes rx_pkts ... [8 fields] tx_bytes tx_pkts ..."
        let trimmed = line.trim();
        let colon = match trimmed.find(':') {
            Some(p) => p,
            None => continue,
        };
        let iface = trimmed[..colon].trim();
        if iface.is_empty() {
            continue;
        }
        let fields: Vec<&str> = trimmed[colon + 1..].split_ascii_whitespace().collect();
        // fields[0] = rx_bytes, fields[8] = tx_bytes
        let rx_bytes: u64 = fields.first().and_then(|s| s.parse().ok()).unwrap_or(0);
        let tx_bytes: u64 = fields.get(8).and_then(|s| s.parse().ok()).unwrap_or(0);
        out.push_str(&format!("{},{},{}\n", iface, tx_bytes, rx_bytes));
    }
    out
}

#[uniffi::export]
fn check_proc_net_fib_trie() -> CheckOutput {
    check_proc_file("/proc/net/fib_trie")
}

fn find_vpn_iface() -> String {
    unsafe {
        let mut addrs: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut addrs) == 0 {
            let mut ifa = addrs;
            while !ifa.is_null() {
                let entry = &*ifa;
                if !entry.ifa_name.is_null() {
                    let name = cstr_to_str(entry.ifa_name);
                    if is_vpn_iface(&name) && (entry.ifa_flags as u32 & libc::IFF_UP as u32) != 0 {
                        libc::freeifaddrs(addrs);
                        return name;
                    }
                }
                ifa = entry.ifa_next;
            }
            libc::freeifaddrs(addrs);
        }
    }
    "tun0".to_string()
}

#[uniffi::export]
pub fn check_getsockopt_bind() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create socket: {}", last_os_error()));
        }

        let vpn_iface = find_vpn_iface();
        let vpn_bytes = vpn_iface.as_bytes();

        let mut name_buf = [0u8; libc::IFNAMSIZ];
        let optlen = std::cmp::min(vpn_bytes.len(), name_buf.len() - 1);
        name_buf[..optlen].copy_from_slice(&vpn_bytes[..optlen]);

        let set_ret = libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_BINDTODEVICE,
            name_buf.as_ptr().cast(),
            (optlen + 1) as libc::socklen_t,
        );

        if set_ret < 0 {
            let err = std::io::Error::last_os_error();
            let raw = err.raw_os_error().unwrap_or(0);
            libc::close(fd);
            let reason = if raw == libc::ENODEV {
                "kernel module returned ENODEV — interface hidden".to_string()
            } else {
                format!("setsockopt failed: {err}")
            };
            return CheckOutput::pass(format!(
                "setsockopt SO_BINDTODEVICE for '{vpn}' blocked — secure ({reason})",
                vpn = vpn_iface,
            ));
        }

        let mut get_buf = [0u8; libc::IFNAMSIZ];
        let mut get_len = get_buf.len() as libc::socklen_t;
        let get_ret = libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_BINDTODEVICE,
            get_buf.as_mut_ptr().cast(),
            &mut get_len,
        );

        let mut bound_device = String::new();
        if get_ret == 0 && get_len > 0 {
            bound_device = cstr_to_str(get_buf.as_ptr().cast());
        }

        libc::close(fd);

        let detail = format!(
            "setsockopt('{vpn}') returned 0 -> getsockopt() returned: dev='{dev}'",
            vpn = vpn_iface,
            dev = bound_device
        );

        if !bound_device.is_empty() && is_vpn_iface(&bound_device) && is_interface_up(&bound_device)
        {
            CheckOutput::fail(format!(
                "{detail} — leaked VPN interface binding! Kernel setsockopt hook bypassed or inactive!",
                detail = detail
            ))
        } else {
            CheckOutput::pass(format!(
                "{detail} — secure (setsockopt returned 0 but bind did not stick or interface is inactive)",
                detail = detail
            ))
        }
    }
}

#[uniffi::export]
pub fn check_inet_diag() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, 4); // 4 is NETLINK_INET_DIAG
        if fd < 0 {
            let e = std::io::Error::last_os_error();
            if is_selinux_denial(&e) {
                CheckOutput::pass(format!(
                    "inet_diag netlink socket denied by SELinux ({e}) — secure"
                ))
            } else {
                CheckOutput::pass(format!("inet_diag failed with error: {e} — secure"))
            }
        } else {
            libc::close(fd);
            CheckOutput::fail(
                "socket(AF_NETLINK, SOCK_RAW, NETLINK_INET_DIAG) succeeded — potentially leaking socket diagnostics!",
            )
        }
    }
}

#[uniffi::export]
pub fn check_getsockname_spoof() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create socket: {}", last_os_error()));
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8 (endianness-independent since all bytes are 8)

        let ret = libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        );
        if ret < 0 {
            let err = last_os_errno();
            libc::close(fd);
            if err == libc::ECONNREFUSED {
                return CheckOutput::network_blocked(
                    "connect() returned ECONNREFUSED — network access disabled for this app",
                );
            }
            return CheckOutput::fail(format!("connect() failed: {}", last_os_error()));
        }

        let mut local: libc::sockaddr_in = std::mem::zeroed();
        let mut local_len = std::mem::size_of_val(&local) as libc::socklen_t;
        let ret = libc::getsockname(fd, std::ptr::from_mut(&mut local).cast(), &mut local_len);
        if ret < 0 {
            libc::close(fd);
            return CheckOutput::fail(format!("getsockname() failed: {}", last_os_error()));
        }
        libc::close(fd);

        let ip_u32 = u32::from_be(local.sin_addr.s_addr);
        let ip_str = format!(
            "{}.{}.{}.{}",
            (ip_u32 >> 24) & 0xFF,
            (ip_u32 >> 16) & 0xFF,
            (ip_u32 >> 8) & 0xFF,
            ip_u32 & 0xFF
        );

        let mut addrs: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut addrs) != 0 {
            return CheckOutput::fail(format!("getifaddrs error: {}", last_os_error()));
        }

        let mut iface_name = "unknown/none".to_string();
        let mut is_vpn = false;
        let mut found = false;

        let mut ifa = addrs;
        while !ifa.is_null() {
            let entry = &*ifa;
            if !entry.ifa_addr.is_null()
                && (*entry.ifa_addr).sa_family == libc::AF_INET as libc::sa_family_t
            {
                let sin = &*(entry.ifa_addr as *const libc::sockaddr_in);
                if sin.sin_addr.s_addr == local.sin_addr.s_addr {
                    iface_name = cstr_to_str(entry.ifa_name);
                    found = true;
                    if is_vpn_iface(&iface_name)
                        && (entry.ifa_flags as u32 & libc::IFF_UP as u32) != 0
                    {
                        is_vpn = true;
                        break;
                    }
                }
            }
            ifa = entry.ifa_next;
        }
        libc::freeifaddrs(addrs);

        let details = format!("getsockname() returned {ip_str} on interface {iface_name}");
        if is_vpn {
            CheckOutput::fail(format!(
                "{details} — leaked VPN IP address! Kernel getsockname hook bypassed or inactive!"
            ))
        } else if !found {
            CheckOutput::fail(format!(
                "{details} — leaked VPN IP address! (Interface hidden by other hooks, but IP still leaked!)"
            ))
        } else {
            CheckOutput::pass(format!(
                "{details} — secure (physical interface IP returned)"
            ))
        }
    }
}

#[uniffi::export]
pub fn check_netlink_getrule() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            frh: FibRuleHdr,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETRULE;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink RTM_GETRULE denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut total = 0u32;
        let mut leaked_rules = Vec::new();
        let hdr_plus_frh =
            std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<FibRuleHdr>();

        const FRA_IIFNAME: u16 = 3;
        const FRA_OIFNAME: u16 = 4;
        const FRA_TABLE: u16 = 15;
        const FRA_UID_RANGE: u16 = 20;

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWRULE,
                |b, offset, msg_len| {
                    total += 1;
                    let data_start = offset + hdr_plus_frh;
                    let msg_end = offset + msg_len;

                    let frh_ptr = b
                        .as_ptr()
                        .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                        as *const FibRuleHdr;
                    let frh = &*frh_ptr;
                    let mut table_id = frh.table as u32;

                    let mut iifname = String::new();
                    let mut oifname = String::new();
                    let mut has_uid_range = false;
                    let mut uid_start = 0u32;
                    let mut uid_end = 0u32;

                    for_each_rtattr(b, data_start, msg_end, |rta, payload| match rta.rta_type {
                        FRA_IIFNAME => {
                            iifname = cstr_to_str(payload.as_ptr().cast());
                        }
                        FRA_OIFNAME => {
                            oifname = cstr_to_str(payload.as_ptr().cast());
                        }
                        FRA_TABLE if payload.len() >= 4 => {
                            table_id = u32::from_ne_bytes(payload[..4].try_into().unwrap());
                        }
                        FRA_UID_RANGE if payload.len() >= 8 => {
                            uid_start = u32::from_ne_bytes(payload[..4].try_into().unwrap());
                            uid_end = u32::from_ne_bytes(payload[4..8].try_into().unwrap());
                            has_uid_range = true;
                        }
                        _ => {}
                    });

                    let matches_vpn_iif =
                        !iifname.is_empty() && is_vpn_iface(&iifname) && is_interface_up(&iifname);
                    let matches_vpn_oif =
                        !oifname.is_empty() && is_vpn_iface(&oifname) && is_interface_up(&oifname);

                    if matches_vpn_iif || matches_vpn_oif {
                        let iface = if matches_vpn_iif { &iifname } else { &oifname };
                        leaked_rules.push(format!("table={table_id} VPN iface={iface}"));
                    } else if has_uid_range {
                        let my_uid = libc::getuid();
                        if my_uid >= uid_start
                            && my_uid <= uid_end
                            && table_id != 254
                            && table_id != 255
                            && table_id != 253
                            && table_id > 100
                        {
                            leaked_rules.push(format!(
                                "table={table_id} for app UID={my_uid} (range {uid_start}-{uid_end})"
                            ));
                        }
                    }
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        if leaked_rules.is_empty() {
            CheckOutput::pass(format!("{total} routing rules, no VPN leaks detected"))
        } else {
            CheckOutput::fail(format!(
                "leaked VPN policy rules (total {total}):\n  {}",
                leaked_rules.join("\n  ")
            ))
        }
    }
}

#[uniffi::export]
pub fn check_tcp_mss() -> CheckOutput {
    unsafe {
        let udp_fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        let mut udp_mtu = 0;
        if udp_fd >= 0 {
            let mut dest: libc::sockaddr_in = std::mem::zeroed();
            dest.sin_family = libc::AF_INET as libc::sa_family_t;
            dest.sin_port = 53u16.to_be();
            dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

            let ret = libc::connect(
                udp_fd,
                std::ptr::from_ref(&dest).cast(),
                std::mem::size_of_val(&dest) as libc::socklen_t,
            );
            if ret == 0 {
                let mut mtu: libc::c_int = 0;
                let mut len = std::mem::size_of_val(&mtu) as libc::socklen_t;
                let opt_ret = libc::getsockopt(
                    udp_fd,
                    libc::IPPROTO_IP,
                    14, // IP_MTU
                    std::ptr::from_mut(&mut mtu).cast(),
                    &mut len,
                );
                if opt_ret == 0 {
                    udp_mtu = mtu;
                }
            }
            libc::close(udp_fd);
        }

        let tcp_fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0);
        if tcp_fd < 0 {
            return CheckOutput::fail(format!("cannot create TCP socket: {}", last_os_error()));
        }

        let timeout = libc::timeval {
            tv_sec: 0,
            tv_usec: 800000, // 800ms
        };
        libc::setsockopt(
            tcp_fd,
            libc::SOL_SOCKET,
            libc::SO_SNDTIMEO,
            std::ptr::from_ref(&timeout).cast(),
            std::mem::size_of_val(&timeout) as libc::socklen_t,
        );

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        let connect_ret = libc::connect(
            tcp_fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        );

        let mut tcp_mss = 0;
        if connect_ret == 0 {
            let mut mss: libc::c_int = 0;
            let mut len = std::mem::size_of_val(&mss) as libc::socklen_t;
            let opt_ret = libc::getsockopt(
                tcp_fd,
                libc::IPPROTO_TCP,
                libc::TCP_MAXSEG,
                std::ptr::from_mut(&mut mss).cast(),
                &mut len,
            );
            if opt_ret == 0 {
                tcp_mss = mss;
            }
        }
        libc::close(tcp_fd);

        let details = format!("UDP PMTU={udp_mtu}, TCP MSS={tcp_mss}");
        let mut suspicious = false;
        let mut reasons = Vec::new();

        if udp_mtu > 0 && udp_mtu < 1450 {
            suspicious = true;
            reasons.push(format!("UDP PMTU {udp_mtu} < 1450"));
        }
        if tcp_mss > 0 && tcp_mss < 1420 {
            suspicious = true;
            reasons.push(format!("TCP MSS {tcp_mss} < 1420"));
        }

        if suspicious {
            CheckOutput::fail(format!(
                "{details} — encapsulation detected: {}",
                reasons.join(", ")
            ))
        } else {
            CheckOutput::pass(format!("{details} — secure (normal physical MTU/MSS)"))
        }
    }
}

#[uniffi::export]
pub fn check_tcp_info_mss() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0);
        if fd < 0 {
            let err = last_os_errno();
            return if err == libc::ECONNREFUSED {
                CheckOutput::network_blocked("socket() returned ECONNREFUSED — no network access")
            } else {
                CheckOutput::fail(format!("cannot create TCP socket: {}", last_os_error()))
            };
        }

        let tv = libc::timeval {
            tv_sec: 2,
            tv_usec: 0,
        };
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDTIMEO,
            std::ptr::from_ref(&tv).cast(),
            std::mem::size_of_val(&tv) as libc::socklen_t,
        );

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808u32.to_be();

        if libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            libc::close(fd);
            return CheckOutput::pass(format!(
                "TCP connect to 8.8.8.8:53 failed ({}) — cannot probe TCP_INFO",
                last_os_error()
            ));
        }

        let mut info: TcpInfoMss = std::mem::zeroed();
        let mut optlen = std::mem::size_of_val(&info) as libc::socklen_t;
        let ret = libc::getsockopt(
            fd,
            libc::IPPROTO_TCP,
            TCP_INFO,
            std::ptr::from_mut(&mut info).cast(),
            &mut optlen,
        );
        libc::close(fd);

        if ret < 0 {
            return CheckOutput::fail(format!("getsockopt(TCP_INFO) failed: {}", last_os_error()));
        }

        let snd = info.tcpi_snd_mss;
        let rcv = info.tcpi_rcv_mss;
        let mut problems = Vec::new();
        if snd > 0 && snd < 1440 {
            problems.push(format!("tcpi_snd_mss={snd}"));
        }
        if rcv > 0 && rcv < 1440 {
            problems.push(format!("tcpi_rcv_mss={rcv}"));
        }

        if problems.is_empty() {
            CheckOutput::pass(format!(
                "TCP_INFO snd_mss={snd} rcv_mss={rcv} — MSS values normal"
            ))
        } else {
            CheckOutput::fail(format!(
                "TCP_INFO MSS below threshold ({}); VPN encapsulation overhead not spoofed",
                problems.join(", ")
            ))
        }
    }
}

#[uniffi::export]
pub fn check_udp_pmtu() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create UDP socket: {}", last_os_error()));
        }

        // Set IP_MTU_DISCOVER to IP_PMTUDISC_DO (2) to force PMTU discovery (DF flag set)
        let val: libc::c_int = 2; // IP_PMTUDISC_DO
        let opt_ret = libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            10, // IP_MTU_DISCOVER
            std::ptr::from_ref(&val).cast(),
            std::mem::size_of_val(&val) as libc::socklen_t,
        );
        if opt_ret < 0 {
            let err_str = last_os_error();
            libc::close(fd);
            return CheckOutput::fail(format!("cannot set IP_MTU_DISCOVER: {err_str}"));
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        let connect_ret = libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        );
        if connect_ret < 0 {
            let err_str = last_os_error();
            libc::close(fd);
            return CheckOutput::fail(format!("cannot connect UDP socket: {err_str}"));
        }

        // Send a 1472-byte payload. Combined with 20-byte IP header and 8-byte UDP header,
        // it makes a 1500-byte IP packet.
        let payload = vec![0u8; 1472];
        let send_ret = libc::send(fd, payload.as_ptr().cast(), payload.len(), 0);

        let err_no = if send_ret < 0 { last_os_errno() } else { 0 };
        let err_str = if send_ret < 0 {
            last_os_error()
        } else {
            String::new()
        };

        libc::close(fd);

        if send_ret < 0 {
            if err_no == libc::EMSGSIZE {
                CheckOutput::fail(
                    "UDP send 1500 bytes failed with EMSGSIZE — VPN tunnel MTU bottleneck detected"
                        .to_string(),
                )
            } else {
                CheckOutput::fail(format!("UDP send 1500 bytes failed with error: {err_str}"))
            }
        } else {
            CheckOutput::pass(
                "UDP send 1500 bytes succeeded — no PMTU bottleneck (physical interface spoofed/allowed)".to_string()
            )
        }
    }
}

#[uniffi::export]
pub fn check_netlink_getneigh() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            ndm: Ndmsg,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETNEIGH;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink RTM_GETNEIGH denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut neighbors = Vec::new();
        let mut total = 0u32;
        let hdr_plus_ndmsg = std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<Ndmsg>();

        const NDA_DST: u16 = 1;
        const NDA_LLADDR: u16 = 2;

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWNEIGH,
                |b, offset, msg_len| {
                    total += 1;
                    let data_start = offset + hdr_plus_ndmsg;
                    let msg_end = offset + msg_len;

                    let ndm_ptr = b
                        .as_ptr()
                        .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                        as *const Ndmsg;
                    let ndm = &*ndm_ptr;

                    let mut ifname_buf = [0u8; libc::IF_NAMESIZE];
                    let ptr = libc::if_indextoname(
                        ndm.ndm_ifindex as u32,
                        ifname_buf.as_mut_ptr().cast(),
                    );
                    let ifname = if !ptr.is_null() {
                        cstr_to_str(ptr)
                    } else {
                        format!("ifindex_{}", ndm.ndm_ifindex)
                    };

                    let mut ip_str = String::new();
                    let mut mac_str = String::new();

                    for_each_rtattr(b, data_start, msg_end, |rta, payload| match rta.rta_type {
                        NDA_DST => {
                            if payload.len() == 4 {
                                ip_str = format!(
                                    "{}.{}.{}.{}",
                                    payload[0], payload[1], payload[2], payload[3]
                                );
                            } else if payload.len() == 16 {
                                ip_str = "IPv6".to_string();
                            }
                        }
                        NDA_LLADDR if payload.len() == 6 => {
                            mac_str = format!(
                                "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
                                payload[0],
                                payload[1],
                                payload[2],
                                payload[3],
                                payload[4],
                                payload[5]
                            );
                        }
                        _ => {}
                    });

                    neighbors.push((ifname, ip_str, mac_str, ndm.ndm_state));
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        let mut physical_has_mac = false;
        let mut list = Vec::new();
        for (iface, ip, mac, state) in &neighbors {
            let is_phys =
                iface.starts_with("wlan") || iface.starts_with("eth") || iface.starts_with("rmnet");
            if is_phys && !mac.is_empty() {
                physical_has_mac = true;
            }
            list.push(format!("{iface} {ip} mac={mac} state=0x{state:02x}"));
        }

        let details = if list.is_empty() {
            "empty table".to_string()
        } else {
            list.join(", ")
        };

        let has_wlan = neighbors
            .iter()
            .any(|(iface, _, _, _)| iface.starts_with("wlan"));

        if has_wlan && !physical_has_mac {
            CheckOutput::fail(format!(
                "wlan has no ARP neighbors (MACs hidden) — table: {details}"
            ))
        } else {
            CheckOutput::pass(format!("neighbor table: {details}"))
        }
    }
}

// ── helpers shared by qdisc and trim-oracle checks ────────────────────

/// Probe active interface ifindexes via IPv6 link-local bind without enumerating names.
/// ENODEV → no interface; any other result → interface exists at that index.
fn probe_active_ifindexes() -> Vec<u32> {
    unsafe {
        let mut result = Vec::new();
        for i in 1u32..=64 {
            let sock = libc::socket(libc::AF_INET6, libc::SOCK_DGRAM, 0);
            if sock < 0 {
                continue;
            }
            let mut addr: libc::sockaddr_in6 = std::mem::zeroed();
            addr.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            addr.sin6_addr.s6_addr = [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
            addr.sin6_scope_id = i;
            let ret = libc::bind(
                sock,
                std::ptr::from_ref(&addr).cast(),
                std::mem::size_of_val(&addr) as libc::socklen_t,
            );
            let err = if ret < 0 { last_os_errno() } else { 0 };
            libc::close(sock);
            if ret < 0 && err == libc::ENODEV {
                continue;
            }
            result.push(i);
        }
        result
    }
}

/// Dump RTM_GETLINK and return the set of ifindexes visible in the response.
fn visible_ifindexes_from_getlink() -> Result<Vec<u32>, CheckOutput> {
    let fd = open_netlink()?;

    #[repr(C)]
    struct Req {
        nlh: libc::nlmsghdr,
        ifm: Ifinfomsg,
    }

    unsafe {
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETLINK;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 10;

        let mut dest: libc::sockaddr_nl = std::mem::zeroed();
        dest.nl_family = libc::AF_NETLINK as u16;

        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return Err(if is_selinux_denial(&e) {
                CheckOutput::pass(format!("RTM_GETLINK denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("RTM_GETLINK send failed: {e}"))
            });
        }

        let mut buf = [0u8; 32768];
        let mut visible = Vec::new();

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(&buf, len as usize, libc::RTM_NEWLINK, |b, offset, _| {
                let ifm_ptr = b
                    .as_ptr()
                    .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                    as *const Ifinfomsg;
                let ifm = &*ifm_ptr;
                if ifm.ifi_index > 0 {
                    visible.push(ifm.ifi_index as u32);
                }
            });
            if !cont {
                break;
            }
        }
        libc::close(fd);
        Ok(visible)
    }
}

#[uniffi::export]
pub fn check_qdisc_by_ifindex() -> CheckOutput {
    unsafe {
        let existing = probe_active_ifindexes();
        if existing.is_empty() {
            return CheckOutput::pass(
                "no active interfaces found via bind probe — qdisc oracle skipped".to_string(),
            );
        }

        let visible = match visible_ifindexes_from_getlink() {
            Ok(v) => v,
            Err(out) => return out,
        };

        let hidden: Vec<u32> = existing
            .iter()
            .filter(|&&idx| !visible.contains(&idx))
            .copied()
            .collect();

        if hidden.is_empty() {
            return CheckOutput::pass(format!(
                "{} active iface(s), none hidden — qdisc oracle: no hidden interfaces to probe",
                existing.len()
            ));
        }

        let nl_fd = match open_netlink() {
            Ok(fd) => fd,
            Err(out) => return out,
        };

        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            tcm: Tcmsg,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = RTM_GETQDISC;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 11;
        req.tcm.tcm_family = libc::AF_UNSPEC as u8;

        let mut dest: libc::sockaddr_nl = std::mem::zeroed();
        dest.nl_family = libc::AF_NETLINK as u16;

        if libc::sendto(
            nl_fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(nl_fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("RTM_GETQDISC denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("RTM_GETQDISC send failed: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut leaked: Vec<String> = Vec::new();
        let hdr_tcm = std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<Tcmsg>();

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(nl_fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont =
                parse_netlink_msgs(&buf, len as usize, RTM_NEWQDISC, |b, offset, msg_len| {
                    let tcm_ptr = b
                        .as_ptr()
                        .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                        as *const Tcmsg;
                    let tcm = &*tcm_ptr;
                    let iface_idx = tcm.tcm_ifindex as u32;

                    if hidden.contains(&iface_idx) {
                        let data_start = offset + hdr_tcm;
                        let msg_end = offset + msg_len;
                        let mut kind = String::from("unknown");
                        for_each_rtattr(b, data_start, msg_end, |rta, payload| {
                            if rta.rta_type == TCA_KIND && !payload.is_empty() {
                                kind = cstr_to_str(payload.as_ptr().cast());
                            }
                        });
                        leaked.push(format!("ifindex={iface_idx} qdisc={kind}"));
                    }
                });
            if !cont {
                break;
            }
        }
        libc::close(nl_fd);

        if leaked.is_empty() {
            CheckOutput::pass(format!(
                "{} hidden ifindex(es) {:?}: no qdisc info leaked — RTM_GETQDISC filtered",
                hidden.len(),
                hidden
            ))
        } else {
            CheckOutput::fail(format!(
                "qdisc info leaked for hidden ifindex(es): {} — RTM_GETQDISC not filtered by kmod",
                leaked.join(", ")
            ))
        }
    }
}

#[uniffi::export]
pub fn check_loopback_bind_conflict() -> CheckOutput {
    unsafe {
        let ports = [1080, 10808, 1082, 2080, 8080, 2081, 53];
        let mut conflicts = Vec::new();
        for &port in &ports {
            let fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0);
            if fd >= 0 {
                let mut addr: libc::sockaddr_in = std::mem::zeroed();
                addr.sin_family = libc::AF_INET as libc::sa_family_t;
                addr.sin_port = (port as u16).to_be();
                addr.sin_addr.s_addr = 0x0100007f; // 127.0.0.1

                let ret = libc::bind(
                    fd,
                    std::ptr::from_ref(&addr).cast(),
                    std::mem::size_of_val(&addr) as libc::socklen_t,
                );
                let err = last_os_errno();
                libc::close(fd);

                if ret < 0 && err == libc::EADDRINUSE {
                    conflicts.push(port.to_string());
                }
            }
        }

        if conflicts.is_empty() {
            CheckOutput::pass("no loopback port bind conflicts (checked SOCKS/HTTP/DNS ports)")
        } else {
            CheckOutput::fail(format!(
                "loopback port bind conflict detected on ports [{}] (EADDRINUSE) — active proxy/VPN listener detected!",
                conflicts.join(", ")
            ))
        }
    }
}

#[uniffi::export]
pub fn check_bpf_iface_map() -> CheckOutput {
    unsafe {
        let paths = [
            "/sys/fs/bpf/netd_shared/map_netd_iface_index_name_map\0",
            "/sys/fs/bpf/map_netd_iface_index_name_map\0",
        ];

        let mut fd = -1;
        let mut last_err = std::io::Error::from_raw_os_error(0);

        for path in &paths {
            let c_path = std::ffi::CStr::from_bytes_with_nul(path.as_bytes()).unwrap();
            let mut attr = [0u8; 128];
            let path_ptr = c_path.as_ptr() as u64;
            std::ptr::copy_nonoverlapping(&path_ptr, attr.as_mut_ptr().cast(), 1);

            let ret = libc::syscall(libc::SYS_bpf, 7, attr.as_ptr(), attr.len()); // 7 is BPF_OBJ_GET
            if ret >= 0 {
                fd = ret as i32;
                break;
            } else {
                last_err = std::io::Error::last_os_error();
            }
        }

        if fd < 0 {
            if is_selinux_denial(&last_err) {
                return CheckOutput::pass(format!(
                    "bpf(BPF_OBJ_GET) map_netd_iface_index_name_map denied by SELinux ({last_err}) — secure"
                ));
            } else {
                return CheckOutput::pass(format!(
                    "bpf(BPF_OBJ_GET) map_netd_iface_index_name_map failed with: {last_err} — secure"
                ));
            }
        }

        // Map opened successfully! Now let's iterate over keys and values.
        let mut key = 0u32;
        let mut next_key = 0u32;
        let mut all_ifaces = Vec::new();
        let mut vpn_ifaces = Vec::new();
        let mut first = true;

        loop {
            let mut attr = [0u8; 128];
            // map_fd is u32 at offset 0
            std::ptr::copy_nonoverlapping(&(fd as u32), attr.as_mut_ptr().cast(), 1);

            // key is u64 at offset 8
            let key_ptr = if first {
                0u64
            } else {
                std::ptr::from_ref(&key) as u64
            };
            std::ptr::copy_nonoverlapping(&key_ptr, attr.as_mut_ptr().add(8).cast(), 1);

            // next_key is u64 at offset 16
            let next_key_ptr = std::ptr::from_mut(&mut next_key) as u64;
            std::ptr::copy_nonoverlapping(&next_key_ptr, attr.as_mut_ptr().add(16).cast(), 1);

            let ret = libc::syscall(libc::SYS_bpf, 4, attr.as_ptr(), attr.len()); // 4 is BPF_MAP_GET_NEXT_KEY
            if ret < 0 {
                break; // End of map or error
            }

            key = next_key;
            first = false;

            // Lookup the value (interface name) for the found key
            let mut val_attr = [0u8; 128];
            std::ptr::copy_nonoverlapping(&(fd as u32), val_attr.as_mut_ptr().cast(), 1);

            let lookup_key_ptr = std::ptr::from_ref(&key) as u64;
            std::ptr::copy_nonoverlapping(&lookup_key_ptr, val_attr.as_mut_ptr().add(8).cast(), 1);

            let mut ifname_bytes = [0u8; 16]; // IFNAMSIZ
            let val_ptr = ifname_bytes.as_mut_ptr() as u64;
            std::ptr::copy_nonoverlapping(&val_ptr, val_attr.as_mut_ptr().add(16).cast(), 1);

            let lookup_ret = libc::syscall(libc::SYS_bpf, 1, val_attr.as_ptr(), val_attr.len()); // 1 is BPF_MAP_LOOKUP_ELEM
            if lookup_ret == 0 {
                let name = cstr_to_str(ifname_bytes.as_ptr().cast());
                if !name.is_empty() {
                    if is_vpn_iface(&name) && is_interface_up(&name) {
                        vpn_ifaces.push(name.clone());
                    }
                    all_ifaces.push(name);
                }
            }
        }

        libc::close(fd);

        if vpn_ifaces.is_empty() {
            CheckOutput::pass(format!(
                "BPF map read successfully, no VPN interfaces detected (interfaces: [{}])",
                all_ifaces.join(", ")
            ))
        } else {
            CheckOutput::fail(format!(
                "leaked VPN interfaces via direct BPF map read: [{}] inside [{}]!",
                vpn_ifaces.join(", "),
                all_ifaces.join(", ")
            ))
        }
    }
}

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __system_property_get(name: *const libc::c_char, value: *mut libc::c_char) -> libc::c_int;
}

#[cfg(not(target_os = "android"))]
unsafe extern "C" fn __system_property_get(
    _name: *const libc::c_char,
    _value: *mut libc::c_char,
) -> libc::c_int {
    0
}

#[uniffi::export]
pub fn check_system_properties() -> CheckOutput {
    unsafe {
        let keys = [
            "net.dns1",
            "net.dns2",
            "net.dns3",
            "net.dns4",
            "net.vpn.dns1",
            "net.vpn.dns2",
            "dhcp.tun0.dns1",
            "dhcp.tun0.dns2",
            "net.interfaces.default.type",
            "net.interfaces.default.name",
        ];
        let mut found = Vec::new();
        let mut value_buf = [0 as libc::c_char; 96]; // PROP_VALUE_MAX is 92
        for &key in &keys {
            let c_key = std::ffi::CString::new(key).unwrap();
            let len = __system_property_get(c_key.as_ptr(), value_buf.as_mut_ptr());
            if len > 0 {
                let val_str = cstr_to_str(value_buf.as_ptr());
                if !val_str.is_empty() {
                    let is_vpn_val = is_vpn_iface(&val_str)
                        || val_str == "4"
                        || key.contains("tun")
                        || key.contains("vpn");
                    if is_vpn_val {
                        found.push(format!("{key}={val_str}"));
                    }
                }
            }
        }
        if found.is_empty() {
            CheckOutput::pass("no VPN properties found via __system_property_get")
        } else {
            CheckOutput::fail(format!("VPN properties leaked: [{}]", found.join(", ")))
        }
    }
}

#[uniffi::export]
pub fn check_proc_sys_net_conf() -> CheckOutput {
    let paths = [
        "/proc/sys/net/ipv4/conf",
        "/proc/sys/net/ipv6/conf",
        "/sys/class/net",
        "/sys/devices/virtual/net",
        "/proc/sys/net/ipv4/neigh",
        "/proc/sys/net/ipv6/neigh",
    ];
    let test_ifaces = ["tun0", "tun1", "wg0", "wg1", "ppp0", "ppp1"];

    let mut path_results = Vec::new();
    let mut any_failed = false;
    let mut leaked_interfaces = Vec::new();

    for path in &paths {
        let mut leaked_here = Vec::new();
        let mut readdir_denied = false;

        // 1. Try readdir
        match std::fs::read_dir(path) {
            Err(_) => {
                readdir_denied = true;
            }
            Ok(entries) => {
                for entry in entries.flatten() {
                    let name = entry.file_name().to_string_lossy().into_owned();
                    if is_vpn_iface(&name) && is_interface_up(&name) {
                        leaked_here.push(name);
                    }
                }
            }
        }

        // 2. Try Path Existence Oracle
        let mut oracle_denied = false;
        for &iface in &test_ifaces {
            match check_path_via_oracle(path, iface) {
                Some(true) => {
                    if is_interface_up(iface) && !leaked_here.contains(&iface.to_string()) {
                        leaked_here.push(iface.to_string());
                    }
                }
                Some(false) => {}
                None => {
                    oracle_denied = true;
                }
            }
        }

        // Format result for this path
        if !leaked_here.is_empty() {
            any_failed = true;
            for iface in &leaked_here {
                if !leaked_interfaces.contains(iface) {
                    leaked_interfaces.push(iface.clone());
                }
            }
            path_results.push(format!("{path}: NOT OK (leaked {})", leaked_here.join(",")));
        } else if readdir_denied && oracle_denied {
            path_results.push(format!("{path}: OK (blocked)"));
        } else {
            path_results.push(format!("{path}: OK"));
        }
    }

    let details = path_results.join(", ");

    if any_failed {
        CheckOutput::fail(format!(
            "VPN leaked: [{}] — Details: {}",
            leaked_interfaces.join(", "),
            details
        ))
    } else {
        CheckOutput::pass(format!("All paths secure — Details: {}", details))
    }
}

#[uniffi::export]
pub fn check_ioctl_alternative() -> CheckOutput {
    unsafe {
        with_inet_dgram_socket(|fd| {
            let vpn_iface = find_vpn_iface();
            let vpn_bytes = vpn_iface.as_bytes();

            let mut ifr: libc::ifreq = std::mem::zeroed();
            let optlen = std::cmp::min(vpn_bytes.len(), ifr.ifr_name.len() - 1);
            std::ptr::copy_nonoverlapping(
                vpn_bytes.as_ptr(),
                ifr.ifr_name.as_mut_ptr().cast(),
                optlen,
            );

            let mut leaked = Vec::new();

            // 1. SIOCGIFINDEX
            if libc::ioctl(fd, libc::SIOCGIFINDEX as _, &ifr) == 0 {
                let index = *(std::ptr::from_ref(&ifr.ifr_ifru) as *const libc::c_int);
                if index > 0 && is_interface_up(&vpn_iface) {
                    leaked.push(format!("SIOCGIFINDEX={index}"));
                }
            }

            // Helper to extract IPv4 from sockaddr inside union
            let get_ip_from_union = |ifr_ifru: &libc::__c_anonymous_ifr_ifru| -> Option<String> {
                let addr_ptr = std::ptr::from_ref(ifr_ifru) as *const libc::sockaddr;
                if (*addr_ptr).sa_family == libc::AF_INET as libc::sa_family_t {
                    let sin = &*(addr_ptr as *const libc::sockaddr_in);
                    let ip = u32::from_be(sin.sin_addr.s_addr);
                    Some(format!(
                        "{}.{}.{}.{}",
                        (ip >> 24) & 0xff,
                        (ip >> 16) & 0xff,
                        (ip >> 8) & 0xff,
                        ip & 0xff
                    ))
                } else {
                    None
                }
            };

            // 2. SIOCGIFADDR
            std::ptr::write_bytes(&mut ifr.ifr_ifru, 0, 1);
            if libc::ioctl(fd, libc::SIOCGIFADDR as _, &ifr) == 0 && is_interface_up(&vpn_iface) {
                if let Some(ip) = get_ip_from_union(&ifr.ifr_ifru) {
                    leaked.push(format!("SIOCGIFADDR={ip}"));
                }
            }

            // 3. SIOCGIFDSTADDR
            std::ptr::write_bytes(&mut ifr.ifr_ifru, 0, 1);
            if libc::ioctl(fd, libc::SIOCGIFDSTADDR as _, &ifr) == 0 && is_interface_up(&vpn_iface)
            {
                if let Some(ip) = get_ip_from_union(&ifr.ifr_ifru) {
                    leaked.push(format!("SIOCGIFDSTADDR={ip}"));
                }
            }

            // 4. SIOCGIFNETMASK
            std::ptr::write_bytes(&mut ifr.ifr_ifru, 0, 1);
            if libc::ioctl(fd, libc::SIOCGIFNETMASK as _, &ifr) == 0 && is_interface_up(&vpn_iface)
            {
                if let Some(ip) = get_ip_from_union(&ifr.ifr_ifru) {
                    leaked.push(format!("SIOCGIFNETMASK={ip}"));
                }
            }

            // 5. SIOCGIFHWADDR
            std::ptr::write_bytes(&mut ifr.ifr_ifru, 0, 1);
            if libc::ioctl(fd, libc::SIOCGIFHWADDR as _, &ifr) == 0 && is_interface_up(&vpn_iface) {
                let hwaddr_ptr = std::ptr::from_ref(&ifr.ifr_ifru) as *const libc::sockaddr;
                let hw = &(*hwaddr_ptr).sa_data;
                let mac = format!(
                    "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
                    hw[0] as u8, hw[1] as u8, hw[2] as u8, hw[3] as u8, hw[4] as u8, hw[5] as u8
                );
                leaked.push(format!("SIOCGIFHWADDR={mac}"));
            }

            if leaked.is_empty() {
                CheckOutput::pass(format!(
                    "alternative ioctl checks for '{vpn_iface}' passed",
                    vpn_iface = vpn_iface
                ))
            } else {
                CheckOutput::fail(format!(
                    "alternative ioctl leaked '{vpn_iface}' details: [{}]",
                    leaked.join(", ")
                ))
            }
        })
    }
}

#[uniffi::export]
pub fn check_direct_syscall() -> CheckOutput {
    unsafe {
        let fd = libc::syscall(libc::SYS_socket, libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            let e = std::io::Error::last_os_error();
            if is_selinux_denial(&e) {
                return CheckOutput::pass(format!(
                    "direct SYS_socket denied by SELinux ({e}) — secure"
                ));
            } else {
                return CheckOutput::fail(format!("direct SYS_socket failed: {e}"));
            }
        }
        let fd = fd as libc::c_int;

        let vpn_iface = find_vpn_iface();
        let mut ifr: libc::ifreq = std::mem::zeroed();
        let name_bytes = vpn_iface.as_bytes();
        let optlen = std::cmp::min(name_bytes.len(), ifr.ifr_name.len() - 1);
        std::ptr::copy_nonoverlapping(
            name_bytes.as_ptr(),
            ifr.ifr_name.as_mut_ptr().cast(),
            optlen,
        );

        let ret = libc::syscall(
            libc::SYS_ioctl,
            fd,
            libc::SIOCGIFFLAGS as libc::c_long,
            std::ptr::from_mut(&mut ifr) as u64,
        );
        let err = last_os_errno();
        libc::close(fd);

        if ret < 0 {
            if err == libc::ENODEV || err == libc::ENXIO {
                CheckOutput::pass(format!(
                    "direct SYS_ioctl for '{vpn}' returned ENODEV/ENXIO — interface hidden",
                    vpn = vpn_iface
                ))
            } else {
                CheckOutput::fail(format!(
                    "direct SYS_ioctl returned error {err} ({})",
                    last_os_error()
                ))
            }
        } else {
            let flags_ptr = std::ptr::from_ref(&ifr.ifr_ifru) as *const libc::c_short;
            let flags = *flags_ptr as u32;
            if flags & libc::IFF_UP as u32 == 0 {
                CheckOutput::pass(format!(
                    "direct SYS_ioctl returned flags=0x{flags:x} — interface is DOWN/inactive"
                ))
            } else {
                CheckOutput::fail(format!(
                    "direct SYS_ioctl: '{vpn}' is visible! flags=0x{flags:x}",
                    vpn = vpn_iface
                ))
            }
        }
    }
}

#[uniffi::export]
pub fn check_traceroute_rtt() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create UDP socket: {}", last_os_error()));
        }

        let on: libc::c_int = 1;
        if libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            11, // IP_RECVERR
            std::ptr::from_ref(&on).cast(),
            std::mem::size_of_val(&on) as libc::socklen_t,
        ) < 0
        {
            let err = last_os_error();
            libc::close(fd);
            return CheckOutput::pass(format!(
                "setsockopt(IP_RECVERR) not supported or denied: {err}"
            ));
        }

        let ttl: libc::c_int = 1;
        libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            4, // IP_TTL
            std::ptr::from_ref(&ttl).cast(),
            std::mem::size_of_val(&ttl) as libc::socklen_t,
        );

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        if libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            let err = last_os_error();
            libc::close(fd);
            return CheckOutput::fail(format!("connect() failed: {err}"));
        }

        let start = std::time::Instant::now();
        let payload = b"ping";
        if libc::send(fd, payload.as_ptr().cast(), payload.len(), 0) < 0 {
            libc::close(fd);
            return CheckOutput::pass("UDP send failed (normal if no internet connection)");
        }

        let mut fds = libc::pollfd {
            fd,
            events: libc::POLLERR,
            revents: 0,
        };
        let poll_ret = libc::poll(&mut fds, 1, 100); // 100ms timeout
        let rtt = start.elapsed();

        if poll_ret <= 0 {
            libc::close(fd);
            return CheckOutput::pass("no traceroute response within 100ms (timeout)");
        }

        let mut msg: libc::msghdr = std::mem::zeroed();
        let mut sender_addr: libc::sockaddr_in = std::mem::zeroed();
        let mut iov = libc::iovec {
            iov_base: [0u8; 512].as_mut_ptr().cast(),
            iov_len: 512,
        };
        let mut control_buf = [0u8; 512];

        msg.msg_name = std::ptr::from_mut(&mut sender_addr).cast();
        msg.msg_namelen = std::mem::size_of_val(&sender_addr) as libc::socklen_t;
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control_buf.as_mut_ptr().cast();
        msg.msg_controllen = control_buf.len();

        let recv_ret = libc::recvmsg(fd, &mut msg, 64); // MSG_ERRQUEUE is 64
        libc::close(fd);

        if recv_ret < 0 {
            return CheckOutput::pass(format!(
                "poll triggered but recvmsg(MSG_ERRQUEUE) failed: {}",
                last_os_error()
            ));
        }

        let mut gateway_ip = String::new();
        if !msg.msg_control.is_null() && msg.msg_controllen >= 16 {
            let control_ptr = msg.msg_control as *const u8;
            let mut offset = 0;
            while offset + 16 <= msg.msg_controllen {
                let (cmsg_len, cmsg_level, cmsg_type, data_offset) =
                    if std::mem::size_of::<usize>() == 8 {
                        let len = *(control_ptr.add(offset) as *const usize);
                        let level = *(control_ptr.add(offset + 8) as *const libc::c_int);
                        let ty = *(control_ptr.add(offset + 12) as *const libc::c_int);
                        (len, level, ty, 16)
                    } else {
                        let len = *(control_ptr.add(offset) as *const u32) as usize;
                        let level = *(control_ptr.add(offset + 4) as *const libc::c_int);
                        let ty = *(control_ptr.add(offset + 8) as *const libc::c_int);
                        (len, level, ty, 12)
                    };

                if cmsg_len < data_offset || offset + cmsg_len > msg.msg_controllen {
                    break;
                }

                if cmsg_level == libc::IPPROTO_IP && cmsg_type == 11 {
                    // IP_RECVERR is 11
                    if cmsg_len >= data_offset + 16 + 16 {
                        let sin_ptr =
                            control_ptr.add(offset + data_offset + 16) as *const libc::sockaddr_in;
                        if (*sin_ptr).sin_family == libc::AF_INET as libc::sa_family_t {
                            let ip = u32::from_be((*sin_ptr).sin_addr.s_addr);
                            gateway_ip = format!(
                                "{}.{}.{}.{}",
                                (ip >> 24) & 0xff,
                                (ip >> 16) & 0xff,
                                (ip >> 8) & 0xff,
                                ip & 0xff
                            );
                            break;
                        }
                    }
                }

                let aligned_len = (cmsg_len + std::mem::size_of::<usize>() - 1)
                    & !(std::mem::size_of::<usize>() - 1);
                if aligned_len == 0 {
                    break;
                }
                offset += aligned_len;
            }
        }

        if gateway_ip.is_empty() {
            return CheckOutput::pass(format!(
                "traceroute hop 1 RTT={:?}, but gateway IP not found in CMSG",
                rtt
            ));
        }

        let is_suspicious = gateway_ip.starts_with("10.8.")
            || gateway_ip.starts_with("10.0.")
            || gateway_ip.starts_with("172.16.")
            || gateway_ip == "127.0.0.1";

        let details = format!("traceroute hop 1: gateway={gateway_ip} RTT={:?}", rtt);
        if is_suspicious {
            CheckOutput::fail(format!(
                "{details} — suspicious first hop (VPN gateway-like IP)"
            ))
        } else {
            CheckOutput::pass(format!("{details} — secure (normal first hop gateway)"))
        }
    }
}

#[inline(always)]
fn read_cntvct() -> u64 {
    #[cfg(target_arch = "aarch64")]
    let val: u64;
    #[cfg(target_arch = "aarch64")]
    unsafe {
        std::arch::asm!("mrs {}, cntvct_el0", out(reg) val, options(nomem, nostack, preserves_flags));
    }
    #[cfg(not(target_arch = "aarch64"))]
    let val = std::time::SystemTime::now()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos() as u64;
    val
}

#[uniffi::export]
pub fn check_arm_timing() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM | libc::SOCK_NONBLOCK, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create UDP socket: {}", last_os_error()));
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        let payload = [0u8; 32];
        let mut min_cycles = u64::MAX;
        let mut max_cycles = 0;
        let mut sum_cycles = 0u64;
        let iterations = 100;
        let mut success_count = 0;

        for _ in 0..iterations {
            let start = read_cntvct();
            let ret = libc::sendto(
                fd,
                payload.as_ptr().cast(),
                payload.len(),
                0,
                std::ptr::from_ref(&dest).cast(),
                std::mem::size_of_val(&dest) as libc::socklen_t,
            );
            let end = read_cntvct();

            if ret >= 0 {
                let diff = end.saturating_sub(start);
                if diff < min_cycles {
                    min_cycles = diff;
                }
                if diff > max_cycles {
                    max_cycles = diff;
                }
                sum_cycles += diff;
                success_count += 1;
            }
        }

        libc::close(fd);

        if success_count == 0 {
            return CheckOutput::pass("UDP sendto failed (normal if no network route)");
        }

        let avg_cycles = sum_cycles / success_count;
        CheckOutput::pass(format!(
            "ARM CNTVCT cycles for sendto: min={}, max={}, avg={}",
            min_cycles, max_cycles, avg_cycles
        ))
    }
}

#[uniffi::export]
pub fn check_udp_queue_pressure() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM | libc::SOCK_NONBLOCK, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create UDP socket: {}", last_os_error()));
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        let payload = [0u8; 32];
        let mut success_count = 0;

        let start_time = std::time::Instant::now();
        let timeout = std::time::Duration::from_millis(100);

        while success_count < 1000 {
            let ret = libc::sendto(
                fd,
                payload.as_ptr().cast(),
                payload.len(),
                0,
                std::ptr::from_ref(&dest).cast(),
                std::mem::size_of_val(&dest) as libc::socklen_t,
            );
            if ret >= 0 {
                success_count += 1;
            } else {
                let err = last_os_errno();
                if err != libc::EAGAIN && err != libc::EWOULDBLOCK {
                    // Stop on hard errors (like ENETUNREACH if network goes down)
                    break;
                }
            }
            if start_time.elapsed() >= timeout {
                break;
            }
        }

        libc::close(fd);

        let elapsed_ms = start_time.elapsed().as_millis();
        let details = format!("Sent {}/1000 packets in {} ms", success_count, elapsed_ms);

        if success_count == 1000 {
            // High packet sending rate achieved -> Rate limiter is not active -> VPN is not hidden
            CheckOutput::fail(format!("VPN detected (high transmission rate): {details}"))
        } else {
            // Hitting rate limit -> Rate limiter active (either simulated by kmod, or slow network) -> Pass
            CheckOutput::pass(format!(
                "No VPN detected (transmission rate limited): {details}"
            ))
        }
    }
}

#[repr(C)]
struct sock_extended_err {
    ee_errno: u32,
    ee_origin: u8,
    ee_type: u8,
    ee_code: u8,
    ee_pad: u8,
    ee_info: u32,
    ee_data: u32,
}

fn find_active_physical_ip_and_mask() -> Option<(u32, u32)> {
    unsafe {
        let mut addrs: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut addrs) == 0 {
            let mut ifa = addrs;
            while !ifa.is_null() {
                let entry = &*ifa;
                if !entry.ifa_name.is_null() && !entry.ifa_addr.is_null() {
                    let name = cstr_to_str(entry.ifa_name);
                    let is_up = (entry.ifa_flags as u32 & libc::IFF_UP as u32) != 0;
                    let is_loopback = (entry.ifa_flags as u32 & libc::IFF_LOOPBACK as u32) != 0;
                    let is_vpn =
                        is_vpn_iface(&name) || name.starts_with("tun") || name.starts_with("ppp");

                    if is_up
                        && !is_loopback
                        && !is_vpn
                        && (*entry.ifa_addr).sa_family == libc::AF_INET as libc::sa_family_t
                    {
                        let sin = &*(entry.ifa_addr as *const libc::sockaddr_in);
                        let ip = u32::from_be(sin.sin_addr.s_addr);
                        let mask = if !entry.ifa_netmask.is_null() {
                            let sin_mask = &*(entry.ifa_netmask as *const libc::sockaddr_in);
                            u32::from_be(sin_mask.sin_addr.s_addr)
                        } else {
                            0xFFFFFF00
                        };
                        libc::freeifaddrs(addrs);
                        return Some((ip, mask));
                    }
                }
                ifa = entry.ifa_next;
            }
            libc::freeifaddrs(addrs);
        }
    }
    None
}

#[allow(clippy::unnecessary_cast)]
fn get_ipv6_recverr(msg: &libc::msghdr) -> Option<u32> {
    unsafe {
        if msg.msg_control.is_null()
            || msg.msg_controllen < std::mem::size_of::<libc::cmsghdr>() as _
        {
            return None;
        }
        let mut cmsg = msg.msg_control as *const libc::cmsghdr;
        let control_end = (msg.msg_control as usize).checked_add(msg.msg_controllen as usize)?;
        while !cmsg.is_null()
            && (cmsg as usize).checked_add(std::mem::size_of::<libc::cmsghdr>())? <= control_end
        {
            let entry = &*cmsg;
            if entry.cmsg_level == libc::IPPROTO_IPV6 && entry.cmsg_type == 25 {
                // IPV6_RECVERR is 25
                let data_ptr = (cmsg as usize + cmsg_align(std::mem::size_of::<libc::cmsghdr>()))
                    as *const sock_extended_err;
                if (data_ptr as usize).checked_add(std::mem::size_of::<sock_extended_err>())?
                    <= control_end
                {
                    return Some((*data_ptr).ee_errno);
                }
            }
            let next_ptr = cmsg as usize + cmsg_align(entry.cmsg_len as usize);
            if next_ptr == cmsg as usize || next_ptr >= control_end {
                break;
            }
            cmsg = next_ptr as *const libc::cmsghdr;
        }
    }
    None
}

fn cmsg_align(len: usize) -> usize {
    let align = std::mem::size_of::<usize>();
    (len + align - 1) & !(align - 1)
}

#[uniffi::export]
pub fn check_gso_asymmetry() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create socket: {}", last_os_error()));
        }

        let gso_size: libc::c_int = 1200;
        let opt_ret = libc::setsockopt(
            fd,
            libc::IPPROTO_UDP,
            103, // UDP_SEGMENT
            std::ptr::from_ref(&gso_size).cast(),
            std::mem::size_of_val(&gso_size) as libc::socklen_t,
        );

        if opt_ret < 0 {
            let err_no = last_os_errno();
            libc::close(fd);
            if err_no == libc::ENOPROTOOPT || err_no == libc::EOPNOTSUPP {
                return CheckOutput::fail(
                    "UDP_SEGMENT GSO offload option not supported by the kernel/socket — VPN/virtual interface asymmetry detected".to_string()
                );
            } else {
                return CheckOutput::fail(format!(
                    "setsockopt UDP_SEGMENT failed: {}",
                    last_os_error()
                ));
            }
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        if libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            let err = last_os_error();
            libc::close(fd);
            return CheckOutput::fail(format!("connect failed: {}", err));
        }

        let large_buffer = vec![0u8; 10000];

        let start = std::time::Instant::now();
        let send_ret = libc::send(fd, large_buffer.as_ptr().cast(), large_buffer.len(), 0);
        let duration = start.elapsed();

        let err_no = if send_ret < 0 { last_os_errno() } else { 0 };
        let err_str = if send_ret < 0 {
            last_os_error()
        } else {
            String::new()
        };
        libc::close(fd);

        if send_ret < 0 {
            if err_no == libc::EOPNOTSUPP
                || err_no == libc::EIO
                || err_no == libc::EMSGSIZE
                || err_no == libc::EINVAL
            {
                CheckOutput::fail(format!(
                    "GSO large send failed: {err_str} (errno {err_no}) — Virtual interface GSO unsupported / VPN detected"
                ))
            } else {
                CheckOutput::fail(format!("GSO send failed with error: {err_str}"))
            }
        } else {
            let duration_us = duration.as_micros();
            CheckOutput::pass(format!(
                "GSO send succeeded with hardware offload latency ({} us) — physical interface",
                duration_us
            ))
        }
    }
}

#[uniffi::export]
pub fn check_timestamping_hw() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            let err = last_os_errno();
            return if err == libc::ECONNREFUSED {
                CheckOutput::network_blocked("socket() ECONNREFUSED — no network access")
            } else {
                CheckOutput::fail(format!("cannot create UDP socket: {}", last_os_error()))
            };
        }

        // Request hardware + software TX timestamps
        let ts_flags: u32 = SOF_TIMESTAMPING_TX_HARDWARE
            | SOF_TIMESTAMPING_TX_SOFTWARE
            | SOF_TIMESTAMPING_RAW_HARDWARE
            | SOF_TIMESTAMPING_OPT_TSONLY;

        if libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            SO_TIMESTAMPING,
            std::ptr::from_ref(&ts_flags).cast(),
            std::mem::size_of_val(&ts_flags) as libc::socklen_t,
        ) < 0
        {
            libc::close(fd);
            return CheckOutput::pass(format!(
                "SO_TIMESTAMPING setsockopt unsupported: {}",
                last_os_error()
            ));
        }

        // Read back flags — kmod strips HW bits before the kernel sets sk_tsflags
        let mut actual_flags: u32 = 0;
        let mut optlen = std::mem::size_of_val(&actual_flags) as libc::socklen_t;
        if libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            SO_TIMESTAMPING,
            std::ptr::from_mut(&mut actual_flags).cast(),
            &mut optlen,
        ) < 0
        {
            libc::close(fd);
            return CheckOutput::pass(format!(
                "SO_TIMESTAMPING getsockopt failed: {}",
                last_os_error()
            ));
        }

        let hw_bits = SOF_TIMESTAMPING_TX_HARDWARE | SOF_TIMESTAMPING_RAW_HARDWARE;
        if (actual_flags & hw_bits) == 0 {
            // kmod stripped hardware bits before the kernel could store them
            libc::close(fd);
            return CheckOutput::pass(format!(
                "SO_TIMESTAMPING hw bits stripped (req=0x{ts_flags:x} got=0x{actual_flags:x}) — kmod hiding hardware timestamp capability"
            ));
        }

        // Hardware bits survived — send a packet and check actual timestamps
        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808u32.to_be();

        if libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            libc::close(fd);
            return CheckOutput::pass(format!(
                "hw bits present (0x{actual_flags:x}) but connect failed — cannot verify actual timestamps"
            ));
        }

        let data = [0u8; 1];
        if libc::send(fd, data.as_ptr().cast(), 1, 0) < 0 {
            libc::close(fd);
            return CheckOutput::pass(format!(
                "hw bits present but send failed: {}",
                last_os_error()
            ));
        }

        // Wait up to 150 ms for the TX timestamp via MSG_ERRQUEUE
        let tv = libc::timeval {
            tv_sec: 0,
            tv_usec: 150_000,
        };
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            std::ptr::from_ref(&tv).cast(),
            std::mem::size_of_val(&tv) as libc::socklen_t,
        );

        let mut ctrl_buf = [0u8; 256];
        let mut data_buf = [0u8; 16];
        let mut iov = libc::iovec {
            iov_base: data_buf.as_mut_ptr().cast(),
            iov_len: data_buf.len(),
        };
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl_buf.as_mut_ptr().cast();
        msg.msg_controllen = ctrl_buf.len();

        let recv_ret = libc::recvmsg(fd, &mut msg, libc::MSG_ERRQUEUE);
        libc::close(fd);

        if recv_ret < 0 {
            return CheckOutput::pass(format!(
                "hw bits present (0x{actual_flags:x}) but MSG_ERRQUEUE timed out — cannot determine hw ts availability"
            ));
        }

        // Parse cmsghdr for SCM_TIMESTAMPING
        let mut hw_sec: i64 = 0;
        let mut hw_nsec: i64 = 0;
        let mut sw_sec: i64 = 0;
        let mut sw_nsec: i64 = 0;
        let mut found = false;

        let mut cmsg_ptr = libc::CMSG_FIRSTHDR(&msg as *const libc::msghdr);
        while !cmsg_ptr.is_null() {
            let cmsg = &*cmsg_ptr;
            if cmsg.cmsg_level == libc::SOL_SOCKET && cmsg.cmsg_type == SCM_TIMESTAMPING {
                let ts = &*(libc::CMSG_DATA(cmsg_ptr) as *const ScmTimestamping);
                sw_sec = ts.ts[0][0];
                sw_nsec = ts.ts[0][1];
                hw_sec = ts.ts[2][0];
                hw_nsec = ts.ts[2][1];
                found = true;
                break;
            }
            cmsg_ptr = libc::CMSG_NXTHDR(&msg as *const libc::msghdr, cmsg_ptr);
        }

        if !found {
            return CheckOutput::pass(format!(
                "hw bits present (0x{actual_flags:x}) but no SCM_TIMESTAMPING in MSG_ERRQUEUE"
            ));
        }

        let has_hw = hw_sec != 0 || hw_nsec != 0;
        let has_sw = sw_sec != 0 || sw_nsec != 0;

        if has_hw {
            CheckOutput::pass(format!(
                "hardware timestamp received (ts[2]={hw_sec}.{hw_nsec:09}ns) — physical NIC"
            ))
        } else if has_sw {
            CheckOutput::fail(format!(
                "SO_TIMESTAMPING: hw bits 0x{actual_flags:x} set but ts[2]=0; sw ts[0]={sw_sec}.{sw_nsec:09}ns — software-only path (virtual interface / VPN)"
            ))
        } else {
            CheckOutput::pass(
                "hw bits present but no timestamps in SCM_TIMESTAMPING — NIC lacks hw ts support"
                    .to_string(),
            )
        }
    }
}

#[uniffi::export]
pub fn check_ipv6_link_local_bruteforce() -> CheckOutput {
    unsafe {
        let mut active_indices: Vec<u32> = Vec::new();
        let mut named_vpn: Vec<String> = Vec::new();
        // Indices that are anonymous OR are named VPN interfaces (tun, ppp, etc.).
        let mut probably_tunnel_indices: Vec<u32> = Vec::new();

        // Pass 1: blind bind probe — discover active interface indices without enumerating them.
        // A fresh socket per iteration avoids the "already bound" problem and keeps each probe
        // independent.  ENODEV means no interface at this index; any other outcome (EADDRNOTAVAIL,
        // EACCES, EPERM, or success) means the kernel acknowledges the interface exists.
        for i in 1u32..=64 {
            let sock = libc::socket(libc::AF_INET6, libc::SOCK_DGRAM, 0);
            if sock < 0 {
                continue;
            }
            let mut addr: libc::sockaddr_in6 = std::mem::zeroed();
            addr.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            addr.sin6_port = 0u16.to_be();
            addr.sin6_addr.s6_addr = [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
            addr.sin6_scope_id = i;
            let ret = libc::bind(
                sock,
                std::ptr::from_ref(&addr).cast(),
                std::mem::size_of_val(&addr) as libc::socklen_t,
            );
            let err_no = if ret < 0 { last_os_errno() } else { 0 };
            libc::close(sock);

            if ret < 0 && err_no == libc::ENODEV {
                continue;
            }
            active_indices.push(i);

            // Step 1a: if_indextoname (bionic reads /sys/class/net; kmod may block this path).
            let mut ifname_buf = [0u8; libc::IF_NAMESIZE];
            let ptr = libc::if_indextoname(i, ifname_buf.as_mut_ptr().cast());
            // Step 1b: if if_indextoname failed, try raw SIOCGIFNAME ioctl.
            // bionic reads /sys/class/net; SIOCGIFNAME goes through dev_ioctl() which kmod
            // may not hook even when it hides the sysfs/netlink entry.
            let opt_name: Option<String> = if !ptr.is_null() {
                Some(cstr_to_str(ptr))
            } else {
                let sock_n = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
                if sock_n < 0 {
                    None
                } else {
                    // sizeof(ifreq) = IFNAMSIZ(16) + union(16); ifr_ifindex is at offset 16.
                    let mut ifr = [0u8; 32];
                    ifr[16..20].copy_from_slice(&(i as i32).to_ne_bytes());
                    let r = libc::ioctl(
                        sock_n,
                        libc::SIOCGIFNAME as _,
                        ifr.as_mut_ptr() as *mut libc::c_void,
                    );
                    libc::close(sock_n);
                    if r >= 0 {
                        Some(cstr_to_str(ifr.as_ptr().cast()))
                    } else {
                        None
                    }
                }
            };
            match opt_name.as_deref() {
                Some(name) => {
                    // Step 1c: SIOCGIFHWADDR — hardware type is kernel-controlled and cannot be
                    // faked by renaming.  ARPHRD_NONE (65534) = tun/WireGuard/GRE (no MAC, no L2).
                    // ARPHRD_PPP (512) = PPP tunnel.  Both prove conclusively it is a tunnel.
                    let sock_hw = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
                    let arphrd: Option<u16> = if sock_hw >= 0 {
                        let mut ifr_hw = [0u8; 32];
                        let nb = name.as_bytes();
                        ifr_hw[..nb.len().min(15)].copy_from_slice(&nb[..nb.len().min(15)]);
                        let r = libc::ioctl(
                            sock_hw,
                            libc::SIOCGIFHWADDR as _,
                            ifr_hw.as_mut_ptr() as *mut libc::c_void,
                        );
                        libc::close(sock_hw);
                        if r >= 0 {
                            Some(u16::from_ne_bytes([ifr_hw[16], ifr_hw[17]]))
                        } else {
                            None
                        }
                    } else {
                        None
                    };
                    // ARPHRD_NONE = 65534, ARPHRD_PPP = 512
                    let is_hw_tunnel = arphrd.is_some_and(|t| t == 65534 || t == 512);
                    let arphrd_note = arphrd.map_or(String::new(), |t| format!(",arphrd={t}"));
                    if is_hw_tunnel || (is_vpn_iface(name) && is_interface_up(name)) {
                        named_vpn.push(format!("idx={i}({name}{arphrd_note})"));
                        probably_tunnel_indices.push(i);
                    }
                }
                None => {
                    // Both if_indextoname and SIOCGIFNAME failed → name truly hidden by kmod.
                    probably_tunnel_indices.push(i);
                }
            }
        }

        // Passes 2-4 operate on the probe pool.  Normally that is probably_tunnel_indices (active
        // indices where if_indextoname returned NULL — name hidden by kmod, or returned a VPN name).
        // If that list is empty the kernel may be intercepting if_indextoname itself; as a fallback
        // probe the 10 indices immediately beyond the highest active one (tun0 always gets the
        // largest ifindex on the device).
        let fallback_indices: Vec<u32> =
            if probably_tunnel_indices.is_empty() && !active_indices.is_empty() {
                let last = *active_indices.last().unwrap();
                ((last + 1)..=(last + 20)).collect()
            } else {
                Vec::new()
            };
        let probe_pool: &[u32] = if !probably_tunnel_indices.is_empty() {
            &probably_tunnel_indices
        } else {
            &fallback_indices
        };
        // In fallback mode ENODEV on IPV6_ADD_MEMBERSHIP just means "no interface at this
        // index", not "no IFF_MULTICAST" — most fallback indices have no interface at all.
        let fallback_mode = !fallback_indices.is_empty();

        // Pass 3: NDP Timeout Oracle (3.5 s per candidate, at most 2 anonymous indices).
        // On a physical interface the kernel sends NDP "who has fe80::dead:beef:cafe:1234?" and
        // waits.  After ~3 retransmissions with no reply it posts EHOSTUNREACH to MSG_ERRQUEUE.
        // A tunnel (NOARP + POINTOPOINT) skips NDP entirely: the packet enters the pipe silently,
        // no error is posted, and the error queue stays empty after the wait.
        let mut ndp_tunnel: Vec<u32> = Vec::new();
        let ndp_candidates: Vec<u32> = probe_pool
            .iter()
            .copied()
            .rev() // highest index first — tun0 typically has the largest ifindex
            .collect();
        for idx in ndp_candidates {
            let sock6 = libc::socket(libc::AF_INET6, libc::SOCK_DGRAM, 0);
            if sock6 < 0 {
                continue;
            }
            let val: libc::c_int = 1;
            // IPV6_RECVERR = 25
            libc::setsockopt(
                sock6,
                libc::IPPROTO_IPV6,
                25,
                std::ptr::from_ref(&val).cast(),
                std::mem::size_of_val(&val) as libc::socklen_t,
            );
            let mut dest: libc::sockaddr_in6 = std::mem::zeroed();
            dest.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            dest.sin6_port = 53u16.to_be();
            // "dead" link-local — guaranteed non-existent, so NDP never gets a reply
            dest.sin6_addr.s6_addr = [
                0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0xde, 0xad, 0xbe, 0xef, 0xca, 0xfe, 0x12, 0x34,
            ];
            dest.sin6_scope_id = idx;
            let send_ret = libc::sendto(
                sock6,
                b"ping".as_ptr().cast(),
                4,
                0,
                std::ptr::from_ref(&dest).cast(),
                std::mem::size_of_val(&dest) as libc::socklen_t,
            );
            if send_ret < 0 {
                // Synchronous failure (ENODEV, ENETUNREACH, …): no interface or no L3 path.
                // MSG_ERRQUEUE stays empty for a non-async reason — skip to avoid false positive.
                libc::close(sock6);
                continue;
            }
            // Wait for NDP retransmission timeout (3 x default RETRANS_TIMER ≈ 3 s)
            std::thread::sleep(std::time::Duration::from_millis(3500));
            let mut iov_buf = [0u8; 512];
            let mut iov = libc::iovec {
                iov_base: iov_buf.as_mut_ptr().cast(),
                iov_len: iov_buf.len(),
            };
            let mut cmsg_buf = [0u64; 64];
            let mut msg: libc::msghdr = std::mem::zeroed();
            msg.msg_iov = &mut iov;
            msg.msg_iovlen = 1;
            msg.msg_control = cmsg_buf.as_mut_ptr().cast();
            msg.msg_controllen = (cmsg_buf.len() * 8) as _;
            let ret = libc::recvmsg(sock6, &mut msg, libc::MSG_ERRQUEUE | libc::MSG_DONTWAIT);
            libc::close(sock6);

            let is_tunnel = if ret < 0 {
                let e = last_os_errno();
                e == libc::EAGAIN || e == libc::EWOULDBLOCK
            } else {
                // A real NDP timeout on a physical interface yields EHOSTUNREACH.
                // Any other error (like EAGAIN, ENOBUFS, ENETUNREACH, etc. from throttling or routing)
                // means we did not get a real NDP timeout, so it is a tunnel or blocked interface.
                if let Some(err_code) = get_ipv6_recverr(&msg) {
                    err_code != libc::EHOSTUNREACH as u32
                } else {
                    true
                }
            };
            if is_tunnel {
                ndp_tunnel.push(idx);
            }
        }

        // Pass 4: Hardware Qdisc Flood (non-blocking, no sleep).
        // Physical wlan0/rmnet are backed by hardware TX ring buffers (txqueuelen ≈ 1000).
        // Non-blocking sends fill them in < 1 ms; the kernel stalls with ENOBUFS/EAGAIN.
        // tun0 has no radio: the kernel RAM queue absorbs far more sends before back-pressure.
        // Threshold: > 5000 successful sends before any stall → RAM-backed queue → tunnel.
        let flood_buf = [0u8; 64];
        let mut qdisc_tunnel: Vec<u32> = Vec::new();
        for &idx in probe_pool {
            let sock6 = libc::socket(libc::AF_INET6, libc::SOCK_DGRAM, 0);
            if sock6 < 0 {
                continue;
            }
            let flags = libc::fcntl(sock6, libc::F_GETFL, 0);
            libc::fcntl(sock6, libc::F_SETFL, flags | libc::O_NONBLOCK);
            let mut dest: libc::sockaddr_in6 = std::mem::zeroed();
            dest.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            dest.sin6_port = 53u16.to_be();
            dest.sin6_addr.s6_addr = [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01];
            dest.sin6_scope_id = idx;
            let mut successful_sends = 0u32;
            for _ in 0..10000u32 {
                let ret = libc::sendto(
                    sock6,
                    flood_buf.as_ptr().cast(),
                    flood_buf.len(),
                    0,
                    std::ptr::from_ref(&dest).cast(),
                    std::mem::size_of_val(&dest) as libc::socklen_t,
                );
                if ret > 0 {
                    successful_sends += 1;
                } else {
                    let e = last_os_errno();
                    if e == libc::ENOBUFS || e == libc::EAGAIN || e == libc::EWOULDBLOCK {
                        break;
                    }
                }
            }
            libc::close(sock6);
            if successful_sends > 5000 {
                qdisc_tunnel.push(idx);
            }
        }

        let fallback_note = if fallback_mode {
            format!("; fallback probed {:?}", fallback_indices)
        } else {
            String::new()
        };
        let details = format!(
            "{} active {:?}; probably_tunnel {:?}{}; \
            ndp_tunnel {:?}; qdisc_tunnel {:?}",
            active_indices.len(),
            active_indices,
            probably_tunnel_indices,
            fallback_note,
            ndp_tunnel,
            qdisc_tunnel,
        );

        if !named_vpn.is_empty() {
            CheckOutput::fail(format!(
                "VPN interfaces exposed via if_indextoname: [{}] — {}",
                named_vpn.join(", "),
                details,
            ))
        } else if !ndp_tunnel.is_empty() {
            let mode = if fallback_mode { " [fallback]" } else { "" };
            CheckOutput::fail(format!(
                "Hidden VPN tunnel detected by NDP timeout oracle{} (NOARP on {:?}) — {}",
                mode, ndp_tunnel, details,
            ))
        } else if !qdisc_tunnel.is_empty() {
            let mode = if fallback_mode { " [fallback]" } else { "" };
            CheckOutput::fail(format!(
                "Hidden VPN tunnel detected by hardware qdisc flood{} (deep queue on {:?}) — {}",
                mode, qdisc_tunnel, details,
            ))
        } else if !probably_tunnel_indices.is_empty() {
            CheckOutput::fail(format!(
                "VPN interfaces exposed via anonymous index leak: {:?} — {}",
                probably_tunnel_indices, details,
            ))
        } else {
            CheckOutput::pass(format!("No VPN detected — {}", details))
        }
    }
}

#[uniffi::export]
pub fn check_uid_route_rules_leak() -> CheckOutput {
    let fd = match open_netlink() {
        Ok(fd) => fd,
        Err(out) => return out,
    };

    unsafe {
        #[repr(C)]
        struct Req {
            nlh: libc::nlmsghdr,
            frh: FibRuleHdr,
        }
        let mut req: Req = std::mem::zeroed();
        req.nlh.nlmsg_len = std::mem::size_of::<Req>() as u32;
        req.nlh.nlmsg_type = libc::RTM_GETRULE;
        req.nlh.nlmsg_flags = (libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16;
        req.nlh.nlmsg_seq = 1;

        let mut dest_addr: libc::sockaddr_nl = std::mem::zeroed();
        dest_addr.nl_family = libc::AF_NETLINK as u16;
        if libc::sendto(
            fd,
            std::ptr::from_ref(&req).cast(),
            req.nlh.nlmsg_len as usize,
            0,
            std::ptr::from_ref(&dest_addr).cast(),
            std::mem::size_of_val(&dest_addr) as libc::socklen_t,
        ) < 0
        {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return if is_selinux_denial(&e) {
                CheckOutput::pass(format!("netlink RTM_GETRULE denied by SELinux ({e})"))
            } else {
                CheckOutput::fail(format!("send error: {e}"))
            };
        }

        let mut buf = [0u8; 32768];
        let mut uid_rules = Vec::new();
        let hdr_plus_frh =
            std::mem::size_of::<libc::nlmsghdr>() + std::mem::size_of::<FibRuleHdr>();

        const FRA_TABLE: u16 = 15;
        const FRA_UID_RANGE: u16 = 20;

        for _ in 0..MAX_NETLINK_RECV_ITERS {
            let len = netlink_recv(fd, &mut buf);
            if len <= 0 {
                break;
            }
            let cont = parse_netlink_msgs(
                &buf,
                len as usize,
                libc::RTM_NEWRULE,
                |b, offset, msg_len| {
                    let data_start = offset + hdr_plus_frh;
                    let msg_end = offset + msg_len;

                    let frh_ptr = b
                        .as_ptr()
                        .add(offset + std::mem::size_of::<libc::nlmsghdr>())
                        as *const FibRuleHdr;
                    let frh = &*frh_ptr;
                    let mut table_id = frh.table as u32;

                    let mut has_uid_range = false;
                    let mut uid_start = 0u32;
                    let mut uid_end = 0u32;

                    for_each_rtattr(b, data_start, msg_end, |rta, payload| match rta.rta_type {
                        FRA_TABLE if payload.len() >= 4 => {
                            table_id = u32::from_ne_bytes(payload[..4].try_into().unwrap());
                        }
                        FRA_UID_RANGE if payload.len() >= 8 => {
                            uid_start = u32::from_ne_bytes(payload[..4].try_into().unwrap());
                            uid_end = u32::from_ne_bytes(payload[4..8].try_into().unwrap());
                            has_uid_range = true;
                        }
                        _ => {}
                    });

                    if has_uid_range
                        && (uid_start >= 10000 || uid_end >= 10000)
                        && uid_end != u32::MAX
                        && (uid_end - uid_start > 0)
                    {
                        uid_rules.push(format!("table={table_id} range={uid_start}-{uid_end}"));
                    }
                },
            );
            if !cont {
                break;
            }
        }
        libc::close(fd);

        if uid_rules.is_empty() {
            CheckOutput::pass(
                "No dynamic UID split routing rules found — physical network behavior".to_string(),
            )
        } else {
            CheckOutput::fail(format!(
                "Split Tunneling routing rules detected (found {} UID range rules): {:?}",
                uid_rules.len(),
                uid_rules
            ))
        }
    }
}

#[uniffi::export]
pub fn check_pmtu_cache_poisoning() -> CheckOutput {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
        if fd < 0 {
            return CheckOutput::fail(format!("cannot create socket: {}", last_os_error()));
        }

        let mut dest: libc::sockaddr_in = std::mem::zeroed();
        dest.sin_family = libc::AF_INET as libc::sa_family_t;
        dest.sin_port = 53u16.to_be();
        dest.sin_addr.s_addr = 0x08080808; // 8.8.8.8

        if libc::connect(
            fd,
            std::ptr::from_ref(&dest).cast(),
            std::mem::size_of_val(&dest) as libc::socklen_t,
        ) < 0
        {
            let err = last_os_error();
            libc::close(fd);
            return CheckOutput::fail(format!("connect to 8.8.8.8 failed: {}", err));
        }

        let mut mtu: libc::c_int = 0;
        let mut len = std::mem::size_of_val(&mtu) as libc::socklen_t;

        let ret = libc::getsockopt(
            fd,
            libc::IPPROTO_IP,
            14, // IP_MTU
            std::ptr::from_mut(&mut mtu).cast(),
            &mut len,
        );

        libc::close(fd);

        if ret < 0 {
            return CheckOutput::fail(format!("getsockopt IP_MTU failed: {}", last_os_error()));
        }

        if mtu > 0 && mtu < 1450 {
            CheckOutput::fail(format!(
                "Path MTU cache leak detected: MTU to 8.8.8.8 is reduced to {} (expected >= 1480) — VPN detected",
                mtu
            ))
        } else {
            CheckOutput::pass(format!("Normal Path MTU: {} bytes", mtu))
        }
    }
}

#[uniffi::export]
pub fn check_underlay_port_conflict() -> CheckOutput {
    unsafe {
        let ip = match find_active_physical_ip_and_mask() {
            Some((ip, _)) => ip,
            None => {
                return CheckOutput::pass(
                    "No active physical IPv4 interface found to scan".to_string(),
                );
            }
        };

        let ip_be = ip.to_be();
        let ip_bytes = ip_be.to_ne_bytes();
        let ip_str = format!(
            "{}.{}.{}.{}",
            ip_bytes[0], ip_bytes[1], ip_bytes[2], ip_bytes[3]
        );

        let ports: [u16; 5] = [500, 4500, 1194, 1701, 51820];
        let mut conflicted_ports = Vec::new();

        for &port in &ports {
            let sock = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
            if sock < 0 {
                continue;
            }

            let mut addr: libc::sockaddr_in = std::mem::zeroed();
            addr.sin_family = libc::AF_INET as libc::sa_family_t;
            addr.sin_port = port.to_be();
            addr.sin_addr.s_addr = ip.to_be();

            let ret = libc::bind(
                sock,
                std::ptr::from_ref(&addr).cast(),
                std::mem::size_of_val(&addr) as libc::socklen_t,
            );

            if ret < 0 {
                let err_no = last_os_errno();
                if err_no == libc::EADDRINUSE {
                    conflicted_ports.push(port);
                }
            }
            libc::close(sock);
        }

        if conflicted_ports.is_empty() {
            CheckOutput::pass(format!(
                "No UDP port conflicts on physical interface IP {} (checked ports: {:?})",
                ip_str, ports
            ))
        } else {
            CheckOutput::fail(format!(
                "UDP Port exhaustion leak: ports {:?} are already bound on physical IP {} — active VPN tunnel underlay socket detected",
                conflicted_ports, ip_str
            ))
        }
    }
}

#[uniffi::export]
pub fn check_rtm_getlink_trim_oracle() -> CheckOutput {
    let existing = probe_active_ifindexes();
    if existing.is_empty() {
        return CheckOutput::pass(
            "no active interfaces found via bind probe — trim oracle skipped".to_string(),
        );
    }

    let visible = match visible_ifindexes_from_getlink() {
        Ok(v) => v,
        Err(out) => return out,
    };

    let hidden: Vec<u32> = existing
        .iter()
        .filter(|&&idx| !visible.contains(&idx))
        .copied()
        .collect();

    if hidden.is_empty() {
        CheckOutput::pass(format!(
            "{} active iface(s) via bind probe, all visible in RTM_GETLINK — no trim-oracle discrepancy",
            existing.len()
        ))
    } else {
        CheckOutput::fail(format!(
            "{} hidden interface(s): ifindex(es) {:?} exist (bind probe) but absent from RTM_GETLINK dump — VPN interface actively hidden (confirms kmod trim)",
            hidden.len(),
            hidden
        ))
    }
}

#[allow(clippy::type_complexity)]
pub fn run_all_checks_cli() {
    let checks: &[(&str, fn() -> CheckOutput)] = &[
        ("check_ioctl_siocgifflags", check_ioctl_siocgifflags),
        ("check_ioctl_siocgifmtu", check_ioctl_siocgifmtu),
        ("check_ioctl_siocgifconf", check_ioctl_siocgifconf),
        ("check_getifaddrs", check_getifaddrs),
        ("check_netlink_getlink", check_netlink_getlink),
        ("check_netlink_getroute", check_netlink_getroute),
        (
            "check_netlink_anonymous_route",
            check_netlink_anonymous_route,
        ),
        ("check_sys_class_net", check_sys_class_net),
        ("check_proc_net_route", check_proc_net_route),
        ("check_proc_net_if_inet6", check_proc_net_if_inet6),
        ("check_proc_net_ipv6_route", check_proc_net_ipv6_route),
        ("check_proc_net_tcp", check_proc_net_tcp),
        ("check_proc_net_tcp6", check_proc_net_tcp6),
        ("check_proc_net_udp", check_proc_net_udp),
        ("check_proc_net_udp6", check_proc_net_udp6),
        ("check_proc_net_dev", check_proc_net_dev),
        ("check_proc_net_fib_trie", check_proc_net_fib_trie),
        ("check_getsockopt_bind", check_getsockopt_bind),
        ("check_inet_diag", check_inet_diag),
        ("check_getsockname_spoof", check_getsockname_spoof),
        ("check_netlink_getrule", check_netlink_getrule),
        ("check_tcp_mss", check_tcp_mss),
        ("check_tcp_info_mss", check_tcp_info_mss),
        ("check_udp_pmtu", check_udp_pmtu),
        ("check_netlink_getneigh", check_netlink_getneigh),
        ("check_qdisc_by_ifindex", check_qdisc_by_ifindex),
        ("check_loopback_bind_conflict", check_loopback_bind_conflict),
        ("check_bpf_iface_map", check_bpf_iface_map),
        ("check_system_properties", check_system_properties),
        ("check_proc_sys_net_conf", check_proc_sys_net_conf),
        ("check_ioctl_alternative", check_ioctl_alternative),
        ("check_direct_syscall", check_direct_syscall),
        ("check_traceroute_rtt", check_traceroute_rtt),
        ("check_arm_timing", check_arm_timing),
        ("check_udp_queue_pressure", check_udp_queue_pressure),
        ("check_gso_asymmetry", check_gso_asymmetry),
        ("check_timestamping_hw", check_timestamping_hw),
        (
            "check_ipv6_link_local_bruteforce",
            check_ipv6_link_local_bruteforce,
        ),
        ("check_uid_route_rules_leak", check_uid_route_rules_leak),
        ("check_pmtu_cache_poisoning", check_pmtu_cache_poisoning),
        ("check_underlay_port_conflict", check_underlay_port_conflict),
        (
            "check_rtm_getlink_trim_oracle",
            check_rtm_getlink_trim_oracle,
        ),
    ];

    println!("=== RUNNING NATIVE VPN HIDE DIAGNOSTIC CHECKS ===");
    let mut passed = 0;
    let mut failed = 0;
    let mut blocked = 0;

    for (name, func) in checks {
        let out = func();
        match out.status {
            CheckStatus::Pass => {
                passed += 1;
                println!("\x1b[32m[ PASS ]\x1b[0m {}: {}", name, out.detail);
            }
            CheckStatus::Fail => {
                failed += 1;
                println!("\x1b[31m[ FAIL ]\x1b[0m {}: {}", name, out.detail);
            }
            CheckStatus::NetworkBlocked => {
                blocked += 1;
                println!("\x1b[33m[ BLOCK]\x1b[0m {}: {}", name, out.detail);
            }
        }
    }

    println!("=================================================");
    println!(
        "Summary: {} passed, {} failed, {} network blocked",
        passed, failed, blocked
    );
}
