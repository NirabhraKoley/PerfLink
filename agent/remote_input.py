"""
Mouse and keyboard control from the phone (the "Remote" page): the phone's touchpad moves the
pointer, clicks and scrolls; its keyboard types text and presses keys and shortcuts.

Uses Windows' SendInput, so it works in any app. PerfLink runs with administrator rights
(start-with-Windows task), so it can also control windows that run as administrator.

Commands (JSON from the phone, "cmd": "input"):
  {"type": "move",   "dx": 12, "dy": -3}              move the pointer by that many pixels
  {"type": "click",  "button": "left"|"right"|"middle", "double": false}
  {"type": "button", "button": "left", "down": true}    press / release (drag)
  {"type": "scroll", "dy": 120, "dx": 0}               wheel units: 120 = one notch, + = up / right
  {"type": "text",   "text": "Hello"}                  type text (any language, symbols, emoji)
  {"type": "key",    "key": "enter", "mods": ["ctrl"]} press a key, optionally with ctrl/alt/shift/win
Mirror page (a touch on the PC's picture; the agent adds the matching desktop position px, py):
  {"type": "tap",    "x": 0.5, "y": 0.3, "button": "left"|"right"}   click at that spot
  {"type": "drag",   "x": 0.5, "y": 0.3, "phase": "start"|"move"|"end"}  press, move, release there
"""
from __future__ import annotations

import ctypes
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x01000
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0001, 0x0002, 0x0004

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT
user32.VkKeyScanW.argtypes = (wintypes.WCHAR,)
user32.VkKeyScanW.restype = ctypes.c_short


def _send(*inputs: INPUT) -> None:
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(flags: int, dx: int = 0, dy: int = 0, data: int = 0) -> INPUT:
    i = INPUT(type=INPUT_MOUSE)
    i.mi = MOUSEINPUT(dx, dy, ctypes.c_ulong(data & 0xFFFFFFFF).value, flags, 0, 0)
    return i


def _key(vk: int = 0, up: bool = False, scan: int = 0, unicode: bool = False) -> INPUT:
    flags = (KEYEVENTF_KEYUP if up else 0) | (KEYEVENTF_UNICODE if unicode else 0)
    if vk in _EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    i = INPUT(type=INPUT_KEYBOARD)
    i.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


# named keys -> virtual-key codes
KEYS = {
    "enter": 0x0D, "tab": 0x09, "esc": 0x1B, "backspace": 0x08, "delete": 0x2E, "insert": 0x2D,
    "space": 0x20, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22, "capslock": 0x14, "printscreen": 0x2C,
    "ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B, "menu": 0x5D,
    "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
    **{f"f{n}": 0x6F + n for n in range(1, 13)},
}
MODS = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B}
_EXTENDED = {0x2E, 0x2D, 0x25, 0x26, 0x27, 0x28, 0x24, 0x23, 0x21, 0x22, 0x5B, 0x5D, 0x2C}
_BUTTONS = {"left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
            "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP)}


def move_to(px: int, py: int) -> None:
    """Put the pointer at a desktop position (pixels; monitors left of / above the main one are negative)."""
    user32.SetCursorPos(int(px), int(py))


def move(dx: float, dy: float) -> None:
    dx, dy = int(round(dx)), int(round(dy))
    if dx or dy:
        _send(_mouse(MOUSEEVENTF_MOVE, dx, dy))


def click(button: str = "left", double: bool = False) -> None:
    down, up = _BUTTONS.get(button, _BUTTONS["left"])
    seq = [_mouse(down), _mouse(up)] * (2 if double else 1)
    _send(*seq)


def press(button: str, is_down: bool) -> None:
    down, up = _BUTTONS.get(button, _BUTTONS["left"])
    _send(_mouse(down if is_down else up))


def scroll(dy: float = 0, dx: float = 0) -> None:
    out = []
    if int(dy):
        out.append(_mouse(MOUSEEVENTF_WHEEL, data=int(dy)))
    if int(dx):
        out.append(_mouse(MOUSEEVENTF_HWHEEL, data=int(dx)))
    if out:
        _send(*out)


def type_text(text: str) -> None:
    """Types any characters (letters, symbols, other languages, emoji) as Unicode key presses."""
    out = []
    for ch in text:
        if ch == "\n":
            out += [_key(KEYS["enter"]), _key(KEYS["enter"], up=True)]
            continue
        units = ch.encode("utf-16-le")
        for k in range(0, len(units), 2):  # characters outside the BMP (emoji) are two UTF-16 units
            code = int.from_bytes(units[k:k + 2], "little")
            out += [_key(scan=code, unicode=True), _key(scan=code, unicode=True, up=True)]
    if out:
        _send(*out)


def key(name: str, mods: list[str] | None = None) -> None:
    """A named key ('enter', 'f5', 'left'…) or a single character ('c'), with modifiers
    (Ctrl+C, Alt+Tab, Win+D, Ctrl+Shift+Esc…)."""
    name = (name or "").strip()
    vk = KEYS.get(name.lower())
    extra_shift = False
    if vk is None and len(name) == 1:
        scan = user32.VkKeyScanW(name)
        if scan == -1:  # not on this keyboard layout: just type it
            type_text(name)
            return
        vk = scan & 0xFF
        extra_shift = bool(scan & 0x100)  # e.g. '!' or 'A' needs Shift on this layout
    if vk is None:
        raise ValueError(f"unknown key {name!r}")
    held = [MODS[m] for m in (mods or []) if m in MODS]
    if extra_shift and MODS["shift"] not in held:
        held.append(MODS["shift"])
    seq = [_key(m) for m in held] + [_key(vk), _key(vk, up=True)] + [_key(m, up=True) for m in reversed(held)]
    _send(*seq)


def handle(msg: dict) -> None:
    t = msg.get("type")
    if t == "move":
        move(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
    elif t == "click":
        click(str(msg.get("button", "left")), bool(msg.get("double")))
    elif t == "button":
        press(str(msg.get("button", "left")), bool(msg.get("down")))
    elif t == "scroll":
        scroll(float(msg.get("dy", 0)), float(msg.get("dx", 0)))
    elif t == "text":
        type_text(str(msg.get("text", ""))[:2000])
    elif t == "key":
        key(str(msg.get("key", "")), [str(m) for m in msg.get("mods") or []])
    elif t in ("tap", "drag") and "px" in msg:
        move_to(msg["px"], msg["py"])
        if t == "tap":
            click(str(msg.get("button", "left")))
        elif msg.get("phase") == "start":
            press("left", True)
        elif msg.get("phase") == "end":
            press("left", False)
