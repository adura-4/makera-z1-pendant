"""Probe the Z1 Wi-Fi board's file commands (ls -s, mkdir, mv, rm) and print every reply frame raw.

    python fsprobe.py <ip> "<command>" ["<command>" ...]

Each command goes out as a 0xA2 line (spaces inside a path written as \\x01 are sent as 0x01). Replies are
collected for 2 s after each command. Read-only commands are safe; mkdir/mv/rm change the SD card.
Research tool for pendant/docs (file browser), 2026-11-16.
"""
from __future__ import annotations

import sys
import time

import z1upload as Z


def run(host: str, commands: list[str]) -> None:
    host, _, port = host.partition(":")
    link = Z._Link(host, int(port) if port else Z.TCP_PORT, 5.0)
    try:
        for cmd in commands:
            payload = cmd.replace("\\x01", "\x01").encode("ascii")
            print(f"\n>>> {cmd!r}")
            link.send(Z.frame(0xA2, payload))
            end = time.monotonic() + 2.0
            while time.monotonic() < end:
                r = link.recv(set(range(256)) - {0x81, 0x71, 0x82}, end - time.monotonic())
                if r is None:
                    break
                t, p = r
                print(f"  {t:#04x} [{len(p)}] {p!r}")
    finally:
        link.close()


if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2:])
