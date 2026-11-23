"""
System-tray icon for the PerfLink agent (like other background apps next to the clock).

Built on .NET WinForms through pythonnet (already used for the hardware sensors), so it
needs no extra packages. The tray runs its own STA thread with a message loop; the agent
just updates `status` and calls `notify()`. A WinForms timer on the tray thread applies
updates, so nothing touches UI objects from other threads.

Menu: status · Open PerfLink log · Open PerfLink folder · Start with Windows (on/off) · Uninstall · Quit
"""
from __future__ import annotations

import os
import subprocess
import threading
import time

import autostart

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "perflink.log")


class Tray:
    def __init__(self, on_quit) -> None:
        self.on_quit = on_quit
        self.status = "Starting…"          # shown in the menu header
        self.tooltip = "PerfLink"          # max 63 characters (Windows limit)
        self._pending: list[tuple[str, str]] = []  # balloons waiting for the UI thread
        self._lock = threading.Lock()
        self._ready = threading.Event()
        self.ok = False
        try:
            import clr  # type: ignore
            clr.AddReference("System.Windows.Forms")
            clr.AddReference("System.Drawing")
            from System.Threading import ApartmentState, Thread, ThreadStart  # type: ignore
            t = Thread(ThreadStart(self._run))
            t.SetApartmentState(ApartmentState.STA)  # WinForms needs a single-threaded apartment
            t.IsBackground = True
            t.Start()
            self._ready.wait(10)
        except Exception as e:  # noqa: BLE001
            print(f"[tray] unavailable: {e}")

    # ------------------------------------------------------------------ public (any thread)
    def set_status(self, status: str, tooltip: str) -> None:
        self.status, self.tooltip = status, tooltip[:63]

    def notify(self, title: str, text: str) -> None:
        with self._lock:
            self._pending.append((title, text))

    # ------------------------------------------------------------------ UI thread
    def _run(self) -> None:
        from System import EventHandler  # type: ignore
        from System.Windows.Forms import (Application, ContextMenuStrip, NotifyIcon, Timer,  # type: ignore
                                          ToolStripMenuItem, ToolStripSeparator, ToolTipIcon)

        icon = NotifyIcon()
        icon.Icon = _make_icon()
        icon.Text = "PerfLink"
        menu = ContextMenuStrip()

        header = ToolStripMenuItem("PerfLink")
        header.Enabled = False
        status_item = ToolStripMenuItem(self.status)
        status_item.Enabled = False
        open_log = ToolStripMenuItem("Open PerfLink log")
        open_dir = ToolStripMenuItem("Open PerfLink folder")
        auto = ToolStripMenuItem("Start with Windows")
        quit_item = ToolStripMenuItem("Quit PerfLink")
        uninstaller = os.path.join(os.path.dirname(HERE), "uninstall.ps1")
        uninstall_item = ToolStripMenuItem("Uninstall PerfLink…")

        def _open_log(s, e):
            if os.path.exists(LOG):
                subprocess.Popen(["notepad.exe", LOG])

        def _open_dir(s, e):
            os.startfile(os.path.dirname(HERE))

        def _toggle_auto(s, e):
            want = not auto.Checked
            ok = autostart.enable() if want else autostart.disable()
            auto.Checked = autostart.is_enabled()
            if not ok:
                icon.ShowBalloonTip(4000, "PerfLink", "Couldn't change 'Start with Windows' "
                                    "(administrator permission was declined).", ToolTipIcon.Warning)

        def _quit(s, e):
            icon.Visible = False
            icon.Dispose()
            Application.ExitThread()
            self.on_quit()

        def _uninstall(s, e):
            # asks for confirmation, then removes PerfLink (this process included)
            subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                              "-File", uninstaller], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

        def _menu_opening(s, e):
            status_item.Text = self.status
            auto.Checked = autostart.is_enabled()

        open_log.Click += EventHandler(_open_log)
        open_dir.Click += EventHandler(_open_dir)
        auto.Click += EventHandler(_toggle_auto)
        quit_item.Click += EventHandler(_quit)
        uninstall_item.Click += EventHandler(_uninstall)
        menu.Opening += _menu_opening  # CancelEventHandler, converted by pythonnet
        items = [header, status_item, ToolStripSeparator(), open_log, open_dir, auto, ToolStripSeparator()]
        root = os.path.dirname(HERE)
        if os.path.exists(uninstaller) and os.path.exists(os.path.join(root, ".perflink-install")) \
                and not os.path.exists(os.path.join(root, ".git")):  # never on a source checkout
            items.append(uninstall_item)
        items.append(quit_item)
        for item in items:
            menu.Items.Add(item)
        icon.ContextMenuStrip = menu
        icon.Visible = True

        timer = Timer()
        timer.Interval = 1000

        def _tick(s, e):
            if icon.Text != self.tooltip:
                icon.Text = self.tooltip
            with self._lock:
                pending, self._pending = self._pending, []
            for title, text in pending[-1:]:  # only the latest, never a pile of pop-ups
                icon.ShowBalloonTip(4000, title, text, ToolTipIcon.Info)

        timer.Tick += EventHandler(_tick)
        timer.Start()
        self.ok = True
        self._ready.set()
        Application.Run()


def _make_icon():
    """32×32 tray icon: dark rounded square with a cyan pulse line (same as the phone app)."""
    from System.Drawing import Bitmap, Color, Graphics, Icon, Pen, Point, Rectangle, SolidBrush  # type: ignore
    from System.Drawing.Drawing2D import GraphicsPath, LineCap, LineJoin, SmoothingMode  # type: ignore
    from System import Array  # type: ignore

    bmp = Bitmap(32, 32)
    g = Graphics.FromImage(bmp)
    g.SmoothingMode = SmoothingMode.AntiAlias
    g.Clear(Color.Transparent)
    path = GraphicsPath()
    r, rect = 7, Rectangle(1, 1, 30, 30)
    path.AddArc(rect.X, rect.Y, r * 2, r * 2, 180, 90)
    path.AddArc(rect.Right - r * 2, rect.Y, r * 2, r * 2, 270, 90)
    path.AddArc(rect.Right - r * 2, rect.Bottom - r * 2, r * 2, r * 2, 0, 90)
    path.AddArc(rect.X, rect.Bottom - r * 2, r * 2, r * 2, 90, 90)
    path.CloseFigure()
    g.FillPath(SolidBrush(Color.FromArgb(255, 11, 14, 19)), path)
    pen = Pen(Color.FromArgb(255, 79, 195, 247), 3.0)
    pen.StartCap = pen.EndCap = LineCap.Round
    pen.LineJoin = LineJoin.Round
    pts = Array[Point]([Point(5, 17), Point(10, 17), Point(13, 9), Point(18, 25),
                        Point(21, 14), Point(23, 17), Point(27, 17)])
    g.DrawLines(pen, pts)
    g.Dispose()
    return Icon.FromHandle(bmp.GetHicon())


def save_icon(path: str) -> None:
    """Write the tray icon to an .ico file (used by the installer for the shortcuts)."""
    import clr  # type: ignore
    clr.AddReference("System.Drawing")
    from System.IO import FileStream, FileMode  # type: ignore
    fs = FileStream(path, FileMode.Create)
    try:
        _make_icon().Save(fs)
    finally:
        fs.Close()


def wait_forever() -> None:  # helper for manual testing: python tray.py
    t = Tray(on_quit=lambda: os._exit(0))
    t.set_status("Test: 0 phones connected", "PerfLink (test)")
    time.sleep(2)
    t.notify("PerfLink", "Tray icon works")
    while True:
        time.sleep(1)


if __name__ == "__main__":
    wait_forever()
