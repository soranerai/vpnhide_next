#!/usr/bin/env -S uv run --script
#
# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "httpx",
#   "rich",
# ]
# ///

import os
import subprocess

import httpx
from rich.console import Console
from rich.table import Table

console = Console()


def github_token() -> str | None:
    if token := os.environ.get("GITHUB_TOKEN"):
        return token
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True)
        return out.stdout.strip() or None
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None


headers = {"Accept": "application/vnd.github+json"}
if token := github_token():
    headers["Authorization"] = f"Bearer {token}"

resp = httpx.get("https://api.github.com/repos/soranerai/vpnhide_next/releases", headers=headers)
resp.raise_for_status()
releases = resp.json()

all_assets = [a for r in releases for a in r["assets"]]
max_count = max((a["download_count"] for a in all_assets), default=1) or 1
name_w = max((len(a["name"]) for a in all_assets), default=0)
count_w = max((len(str(a["download_count"])) for a in all_assets), default=1)
# padding=(0, 1) on 3 columns => 6 chars of horizontal padding, no borders (box=None)
bar_w = max(10, console.width - name_w - count_w - 6)

grand_total = 0
for release in releases:
    assets = release["assets"]
    total = sum(a["download_count"] for a in assets)
    grand_total += total

    table = Table(
        title=f"{release['tag_name']}  ({total} downloads)",
        title_style="bold",
        show_header=False,
        box=None,
        padding=(0, 1),
    )
    table.add_column("Asset", style="cyan")
    table.add_column("Count", justify="right", style="yellow")
    table.add_column("Bar", style="green", no_wrap=True)

    for a in assets:
        bar = "█" * max(1, a["download_count"] * bar_w // max_count)
        table.add_row(a["name"], str(a["download_count"]), bar)

    console.print()
    console.print(table)

console.print(f"\n[bold green]Total: {grand_total} downloads[/bold green]")
