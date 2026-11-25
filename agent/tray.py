"""
System-tray icon for the PerfLink agent (like other background apps next to the clock).

Built on .NET WinForms through pythonnet (already used for the hardware sensors), so it
needs no extra packages. The tray runs its own STA thread with a message loop; the agent
just updates `status` and calls `notify()`. A WinForms timer on the tray thread applies
updates, so nothing touches UI objects from other threads.

Menu: status · Open PerfLink log · Open PerfLink folder · Start with Windows (on/off) · Allowed phones ·
Blocked phones · Check for updates · Uninstall · Quit

ask() shows the "A phone wants to connect: Allow / Block" window for a phone connecting over Wi-Fi.
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
    def __init__(self, on_quit, version: str = "") -> None:
        self.on_quit = on_quit
        self.version = version             # shown in the menu header: "PerfLink 1.22"
        self.on_check_update = None        # set by the agent (installed copies): check GitHub now
        self.on_update_now = None          # set by the agent: open PowerShell and run the install command
        self.get_devices = None            # set by the agent: {"allowed": {id: {...}}, "blocked": {...}}
        self.on_unblock = None             # (id) -> None
        self.on_forget = None              # (id) -> None
        self._asks: list = []              # "allow this phone?" windows waiting for the UI thread
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

    def ask(self, name: str, ip: str, answer, timeout: float = 60) -> bool:
        """Show "<name> (<ip>) wants to connect" with Allow / Block. Calls answer("allow" | "block" | None)
        once, from the UI thread (None: closed, or no answer within `timeout` seconds). False: no tray."""
        if not self.ok:
            return False
        with self._lock:
            self._asks.append((name, ip, answer, timeout))
        return True

    # ------------------------------------------------------------------ UI thread
    def _run(self) -> None:
        from System import EventHandler  # type: ignore
        from System.Windows.Forms import (Application, ContextMenuStrip, NotifyIcon, Timer,  # type: ignore
                                          ToolStripMenuItem, ToolStripSeparator, ToolTipIcon)

        icon = NotifyIcon()
        icon.Icon = _make_icon()
        icon.Text = "PerfLink"
        menu = ContextMenuStrip()

        header = ToolStripMenuItem(f"PerfLink {self.version}".strip())
        header.Enabled = False
        status_item = ToolStripMenuItem(self.status)
        status_item.Enabled = False
        open_log = ToolStripMenuItem("Open PerfLink log")
        open_dir = ToolStripMenuItem("Open PerfLink folder")
        auto = ToolStripMenuItem("Start with Windows")
        quit_item = ToolStripMenuItem("Quit PerfLink")
        update_item = ToolStripMenuItem("Check for updates")
        update_now_item = ToolStripMenuItem("Update now (PowerShell)")
        update_now_item.ToolTipText = "Opens PowerShell and runs the PerfLink install command"
        allowed_menu = ToolStripMenuItem("Allowed phones")
        blocked_menu = ToolStripMenuItem("Blocked phones")
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

        def _check_update(s, e):
            if self.on_check_update:
                threading.Thread(target=self.on_check_update, daemon=True).start()

        def _fill_phone_menus():
            """Allowed / Blocked phones: click one to forget / unblock it (it is asked about again next time)."""
            devs = self.get_devices() if self.get_devices else {"allowed": {}, "blocked": {}}
            for menu_item, key, verb, action in ((allowed_menu, "allowed", "Forget", self.on_forget),
                                                 (blocked_menu, "blocked", "Unblock", self.on_unblock)):
                menu_item.DropDownItems.Clear()
                entries = devs.get(key) or {}
                if not entries:
                    none = ToolStripMenuItem("(none)")
                    none.Enabled = False
                    menu_item.DropDownItems.Add(none)
                    continue
                for did, info in sorted(entries.items(), key=lambda kv: kv[1].get("name", "")):
                    it = ToolStripMenuItem(f"{verb} {info.get('name', 'phone')}  ({info.get('ip', '?')})")
                    it.ToolTipText = f"Since {info.get('since', '?')}"

                    def _click(s, e, did=did, action=action):
                        if action:
                            action(did)
                    it.Click += EventHandler(_click)
                    menu_item.DropDownItems.Add(it)

        def _update_now(s, e):
            if self.on_update_now:
                threading.Thread(target=self.on_update_now, daemon=True).start()

        def _menu_opening(s, e):
            status_item.Text = self.status
            auto.Checked = autostart.is_enabled()
            _fill_phone_menus()

        open_log.Click += EventHandler(_open_log)
        open_dir.Click += EventHandler(_open_dir)
        auto.Click += EventHandler(_toggle_auto)
        quit_item.Click += EventHandler(_quit)
        uninstall_item.Click += EventHandler(_uninstall)
        update_item.Click += EventHandler(_check_update)
        update_now_item.Click += EventHandler(_update_now)
        menu.Opening += _menu_opening  # CancelEventHandler, converted by pythonnet
        items = [header, status_item, ToolStripSeparator(), open_log, open_dir, auto, ToolStripSeparator(),
                 allowed_menu, blocked_menu, ToolStripSeparator()]
        _fill_phone_menus()   # (a submenu needs items to show its arrow)
        root = os.path.dirname(HERE)
        if os.path.exists(uninstaller) and os.path.exists(os.path.join(root, ".perflink-install")) \
                and not os.path.exists(os.path.join(root, ".git")):  # never on a source checkout
            items.append(update_item)
            items.append(update_now_item)
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
            with self._lock:
                asks, self._asks = self._asks, []
            for name, ip, answer, timeout in asks:
                try:
                    _ask_window(name, ip, answer, timeout)
                except Exception as ex:  # noqa: BLE001
                    print(f"[tray] couldn't show the allow window: {ex}")
                    answer(None)

        timer.Tick += EventHandler(_tick)
        timer.Start()
        self.ok = True
        self._ready.set()
        Application.Run()


def _ask_window(name: str, ip: str, answer, timeout: float) -> None:
    """The "phone wants to connect" window (UI thread). answer() is called exactly once."""
    from System import EventHandler  # type: ignore
    from System.Drawing import Color, Font, FontStyle, Point, Size  # type: ignore
    from System.Media import SystemSounds  # type: ignore
    from System.Windows.Forms import (Button, FlatStyle, Form, FormBorderStyle, FormStartPosition,  # type: ignore
                                      Label, Timer)

    done = {"v": False}

    def finish(value):
        if done["v"]:
            return
        done["v"] = True
        try:
            answer(value)
        finally:
            timer.Stop()
            form.Close()

    form = Form()
    form.Text = "PerfLink - a phone wants to connect"
    form.FormBorderStyle = FormBorderStyle.FixedDialog
    form.MaximizeBox = form.MinimizeBox = False
    form.StartPosition = FormStartPosition.CenterScreen
    form.ClientSize = Size(440, 210)
    form.TopMost = True
    form.ShowInTaskbar = True
    form.BackColor = Color.FromArgb(18, 22, 30)
    form.ForeColor = Color.FromArgb(232, 237, 243)
    try:
        form.Icon = _make_icon()
    except Exception:  # noqa: BLE001
        pass

    head = Label()
    head.Text = name
    head.Font = Font("Segoe UI", 14, FontStyle.Bold)
    head.Location = Point(22, 18)
    head.Size = Size(400, 32)
    sub = Label()
    sub.Text = (f"IP {ip}  ·  over Wi-Fi\nwants to connect to this PC: see its stats, screen and sound, "
                "and control the mouse and keyboard.\nOnly allow phones you know.")
    sub.Font = Font("Segoe UI", 9.5)
    sub.ForeColor = Color.FromArgb(170, 180, 196)
    sub.Location = Point(22, 54)
    sub.Size = Size(400, 80)
    left = Label()
    left.Font = Font("Segoe UI", 8.5)
    left.ForeColor = Color.FromArgb(120, 130, 146)
    left.Location = Point(22, 168)
    left.Size = Size(170, 20)

    def button(text, x, back, value):
        b = Button()
        b.Text = text
        b.Font = Font("Segoe UI", 10, FontStyle.Bold)
        b.Location = Point(x, 158)
        b.Size = Size(110, 36)
        b.FlatStyle = FlatStyle.Flat
        b.BackColor = back
        b.ForeColor = Color.White
        b.Click += EventHandler(lambda s, e: finish(value))
        form.Controls.Add(b)
        return b

    button("Block", 196, Color.FromArgb(190, 50, 60), "block")
    allow = button("Allow", 316, Color.FromArgb(40, 150, 90), "allow")
    for c in (head, sub, left):
        form.Controls.Add(c)
    form.AcceptButton = None   # Enter must not allow by accident
    form.FormClosed += EventHandler(lambda s, e: finish(None))   # closed with X: not allowed this time

    end = time.time() + timeout
    timer = Timer()
    timer.Interval = 500

    def tick(s, e):
        rest = int(end - time.time())
        left.Text = f"Closes in {max(rest, 0)} s (not allowed)"
        if rest <= 0:
            finish(None)
    timer.Tick += EventHandler(tick)
    tick(None, None)
    timer.Start()
    form.Show()
    form.Activate()
    try:
        SystemSounds.Exclamation.Play()
    except Exception:  # noqa: BLE001
        pass


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
