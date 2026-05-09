# portshide

Magisk / KernelSU-Next module that blocks selected apps from reaching
`localhost` ports. Used to hide locally-bound VPN / proxy daemons
(Clash, Sing-box, V2Ray, Amnezia, etc.) from apps that probe for them
via `connect(127.0.0.1, PORT)` / `connect(::1, PORT)`.

## How it works

A small shell script installs `iptables` / `ip6tables` rules inside a
dedicated chain `vpnhide_out` / `vpnhide_out6`:

```
iptables -A vpnhide_out -m owner --uid-owner <UID> -d 127.0.0.1 -p tcp -j REJECT --reject-with tcp-reset
iptables -A vpnhide_out -m owner --uid-owner <UID> -d 127.0.0.1 -p udp -j REJECT --reject-with icmp-port-unreachable
```

…for every UID listed in `/data/adb/vpnhide_ports/observers.txt`,
plus the same for `::1` via `ip6tables`. A jump from `OUTPUT` into the
dedicated chain is inserted exactly once (`iptables -C` guarded).

Observer apps receive `ECONNREFUSED` — indistinguishable from a real
closed port. `netd`'s own chains are never touched; our chain lives
beside them and is idempotently re-applied on every config change.

## Install

Pick `vpnhide-ports.zip` in the KernelSU-Next or Magisk manager and
install. Reboot not strictly required — rules apply on next boot via
`service.sh`, or immediately if you manage observers through the VPN
Hide app (it invokes `vpnhide_ports_apply.sh` via `su`).

## Configuration

Managed by the VPNHide Next app (Protection → Ports). Direct shell
alternative:

```
# /data/adb/vpnhide_ports/observers.txt — one UID per line
10451
10422
```

Then:

```sh
su -c sh /data/adb/modules/vpnhide_ports/vpnhide_ports_apply.sh
```

## Why just localhost, and why for selected apps only

- Banking / anti-censorship detection apps probe `127.0.0.1:7890`,
  `127.0.0.1:1080`, `127.0.0.1:8080` etc. Blocking these globally
  would break the VPN client itself (it needs to bind / use them).
  Per-UID REJECT gives surgical control.
- Blocking **all** ports on loopback for observers (rather than a
  port list) is safe for typical observer apps: banks, госуслуги,
  marketplaces, non-browser Yandex apps, VK — none legitimately use
  localhost.
- Browsers (Chromium-based) are the only category with some
  legitimate localhost use (dev tools, PWAs). Just don't add them as
  observers.

## Caveats

- Rules are lost on reboot. `service.sh` restores them early in boot,
  waiting for `netd` to finish its setup first (checks
  `bw_OUTPUT` readiness).
- Some Android versions rebuild `OUTPUT` on network state changes.
  Our rules in our own chain survive; only the `OUTPUT -j vpnhide_out`
  jump can be affected. Re-run apply script if needed; the VPNHide Next
  app's Save action does this automatically.
- `iptables-legacy` backend expected (default on AOSP 16 as of this
  writing). nftables backend via `iptables-nft` also works — same
  syntax, same kernel effect.

## Uninstall

Via root manager — `uninstall.sh` flushes and removes our chains.
