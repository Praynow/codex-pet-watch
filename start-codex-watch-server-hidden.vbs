Option Explicit

Dim shell
Dim fso
Dim projectDir
Dim serverScript
Dim command

Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

projectDir = fso.GetParentFolderName(WScript.ScriptFullName)
serverScript = fso.BuildPath(projectDir, "start-codex-watch-server.ps1")

shell.CurrentDirectory = projectDir
command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File " & Chr(34) & serverScript & Chr(34)
shell.Run command, 0, False
