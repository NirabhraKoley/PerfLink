' PerfLink launcher (used by the Start menu / desktop shortcuts).
' Starts PerfLink in the background with its tray icon, without a console window:
'   1. through the "PerfLink" scheduled task (runs as administrator, no UAC prompt), or
'   2. if that task doesn't exist, directly with pythonw.exe (no admin: FPS may be N/A).
'   The installer records the exact pythonw.exe it set PerfLink up with in agent\pythonw.txt
'   (a PC can have several Pythons; the packages are only installed into that one).
Option Explicit
Dim sh, fso, dir, pyw, f
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
If sh.Run("schtasks.exe /run /tn PerfLink", 0, True) <> 0 Then
    pyw = "pythonw.exe"
    If fso.FileExists(dir & "\agent\pythonw.txt") Then
        Set f = fso.OpenTextFile(dir & "\agent\pythonw.txt", 1)
        pyw = Trim(f.ReadLine)
        f.Close
    End If
    sh.Run """" & pyw & """ """ & dir & "\agent\perflink_agent.py"" --tray", 0, False
End If
