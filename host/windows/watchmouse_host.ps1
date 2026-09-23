#Requires -Version 5.1
<#
WatchMouse Windows host (NOT TESTED -- no Windows machine available for CI).

Listens for the watch's TCP input (see host/README.md for the protocol) and
injects a real mouse + keyboard using SendInput.

Usage:
    powershell -ExecutionPolicy Bypass -File watchmouse_host.ps1 [-Port 8888] [-Bind 0.0.0.0]

Notes:
  * Mouse moves are relative (MOUSEEVENTF_MOVE), buttons/wheel via SendInput.
  * Keys are injected as Set-1 scan codes so the current layout decides the
    character (modifiers included). x/-;'`., etc. then work with any layout.
  * One watch at a time. The listener accepts serial connections.
#>
param(
    [int]$Port = 8888,
    [string]$Bind = '0.0.0.0'
)

$ErrorActionPreference = 'Stop'

$source = @'
using System;
using System.Runtime.InteropServices;

namespace WatchMouse
{
    public static class InputLib
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct POINT { public int X; public int Y; }

        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT
        {
            public int dx; public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT
        {
            public uint type;
            public InputUnion U;
        }

        public const uint INPUT_MOUSE = 0;
        public const uint INPUT_KEYBOARD = 1;

        public const uint MOUSEEVENTF_MOVE = 0x0001;
        public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        public const uint MOUSEEVENTF_LEFTUP = 0x0004;
        public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        public const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        public const uint MOUSEEVENTF_WHEEL = 0x0800;

        public const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        public const uint KEYEVENTF_KEYUP = 0x0002;
        public const uint KEYEVENTF_SCANCODE = 0x0008;

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool SetCursorPos(int X, int Y);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern uint SendInput(uint cInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        public static extern bool GetCursorPos(out POINT lpPoint);

        private static void SendOne(INPUT[] arr)
        {
            SendInput(1, arr, Marshal.SizeOf(typeof(INPUT)));
        }

        public static void MoveRelative(int dx, int dy)
        {
            if (dx == 0 && dy == 0) return;
            INPUT[] inp = new INPUT[1];
            inp[0].type = INPUT_MOUSE;
            inp[0].U.mi.dx = dx;
            inp[0].U.mi.dy = dy;
            inp[0].U.mi.dwFlags = MOUSEEVENTF_MOVE;
            SendOne(inp);
        }

        public static void Wheel(int delta)
        {
            INPUT[] inp = new INPUT[1];
            inp[0].type = INPUT_MOUSE;
            inp[0].U.mi.mouseData = unchecked((uint)delta);
            inp[0].U.mi.dwFlags = MOUSEEVENTF_WHEEL;
            SendOne(inp);
        }

        public static void MouseButton(uint downFlags, uint upFlags, bool down)
        {
            INPUT[] inp = new INPUT[1];
            inp[0].type = INPUT_MOUSE;
            inp[0].U.mi.dwFlags = down ? downFlags : upFlags;
            SendOne(inp);
        }

        public static void KeyScan(byte scan, bool extended, bool down)
        {
            INPUT[] inp = new INPUT[1];
            inp[0].type = INPUT_KEYBOARD;
            inp[0].U.ki.wScan = scan;
            inp[0].U.ki.dwFlags = KEYEVENTF_SCANCODE;
            if (extended) inp[0].U.ki.dwFlags |= KEYEVENTF_EXTENDEDKEY;
            if (!down) inp[0].U.ki.dwFlags |= KEYEVENTF_KEYUP;
            SendOne(inp);
        }
    }
}
'@
Add-Type -TypeDefinition $source -ErrorAction Stop

# ---------------------------------------------------------------- key maps --
$KeyMap = @{}
for ($i = 0; $i -lt 26; $i++) { $KeyMap[0x04 + $i] = @{ ext = $false; scan = 0x1E + $i } } # a..z
for ($i = 0; $i -lt 10; $i++) { $KeyMap[0x1E + $i] = @{ ext = $false; scan = 0x02 + $i } } # 1..0

$Single = @{
    0x2C = 0x39; 0x2D = 0x0C; 0x2E = 0x0D; 0x2F = 0x1A; 0x30 = 0x1B; 0x31 = 0x2B
    0x33 = 0x27; 0x34 = 0x28; 0x35 = 0x29; 0x36 = 0x33; 0x37 = 0x34; 0x38 = 0x35
    40   = 0x1C; 41   = 0x01; 42   = 0x0E; 43   = 0x0F; 44   = 0x39
}
$Extended = @{ 79 = 0x4D; 80 = 0x4B; 81 = 0x50; 82 = 0x48 } # arrows
foreach ($k in $Single.Keys) { $KeyMap[$k] = @{ ext = $false; scan = $Single[$k] } }
foreach ($k in $Extended.Keys) { $KeyMap[$k] = @{ ext = $true; scan = $Extended[$k] } }

# KeyboardHelper.Modifier bitmask -> (scan, extended)
$ModifierMap = @(
    @{ ext = $false; scan = 0x1D } # 0 Left Ctrl
    @{ ext = $false; scan = 0x2A } # 1 Left Shift
    @{ ext = $false; scan = 0x38 } # 2 Left Alt
    @{ ext = $true;  scan = 0x5B } # 3 Left GUI
    @{ ext = $true;  scan = 0x1D } # 4 Right Ctrl
    @{ ext = $false; scan = 0x36 } # 5 Right Shift
    @{ ext = $true;  scan = 0x38 } # 6 Right Alt
    @{ ext = $true;  scan = 0x5C } # 7 Right GUI
)

# --------------------------------------------------------------- mouse state --
$script:PressedButtons = @{ L = $false; R = $false; M = $false }
$script:PressedKeys = @{}

function Invoke-Mouse {
    param([pscustomobject]$M)
    $cur = @{ L = [bool]$M.l; R = [bool]$M.r; M = [bool]$M.m }

    if ($cur.L -ne $script:PressedButtons.L) {
        [WatchMouse.InputLib]::MouseButton(0x0002, 0x0004, $cur.L)
        $script:PressedButtons.L = $cur.L
    }
    if ($cur.R -ne $script:PressedButtons.R) {
        [WatchMouse.InputLib]::MouseButton(0x0008, 0x0010, $cur.R)
        $script:PressedButtons.R = $cur.R
    }
    if ($cur.M -ne $script:PressedButtons.M) {
        [WatchMouse.InputLib]::MouseButton(0x0020, 0x0040, $cur.M)
        $script:PressedButtons.M = $cur.M
    }

    $dx = [int]$M.dx
    $dy = [int]$M.dy
    if ($dx -ne 0 -or $dy -ne 0) {
        [WatchMouse.InputLib]::MoveRelative($dx, $dy)
    }
    $w = [int]$M.w
    if ($w -ne 0) {
        [WatchMouse.InputLib]::Wheel($w * 120)
    }
}

function Invoke-Key {
    param([pscustomobject]$M)
    $next = @{}
    for ($bit = 0; $bit -lt $ModifierMap.Count; $bit++) {
        if (([int64]$M.mod -band (1 -shl $bit)) -ne 0) {
            $mod = $ModifierMap[$bit]
            $next[('{0}:{1}' -f [int]$mod.ext, $mod.scan)] = $true
        }
    }
    foreach ($usage in @($M.k)) {
        $entry = $KeyMap[[int]$usage]
        if ($null -ne $entry) {
            $next[('{0}:{1}' -f [int]$entry.ext, $entry.scan)] = $true
        }
    }
    foreach ($name in $script:PressedKeys.Keys) {
        if (-not $next.ContainsKey($name)) {
            $parts = $name -split ':'
            [WatchMouse.InputLib]::KeyScan([byte]$parts[1], ($parts[0] -eq '1'), $false)
            $script:PressedKeys.Remove($name)
        }
    }
    foreach ($name in $next.Keys) {
        if (-not $script:PressedKeys.ContainsKey($name)) {
            $parts = $name -split ':'
            [WatchMouse.InputLib]::KeyScan([byte]$parts[1], ($parts[0] -eq '1'), $true)
            $script:PressedKeys[$name] = $true
        }
    }
}

# -------------------------------------------------------- TCP receiver loop --
$pingLine = '{"t":"ping"}'

function Handle-Client {
    param($Client)
    $stream = $Client.GetStream()
    $reader = [System.IO.StreamReader]::new($stream, (New-Object System.Text.UTF8Encoding($false)))
    $writer = [System.IO.StreamWriter]::new($stream, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $stream.ReadTimeout = 4000
        $writer.WriteLine('{"t":"hello","app":"WatchMouseHost","v":1}')
        $writer.Flush()
        while ($true) {
            try {
                $line = $reader.ReadLine()
            } catch {
                # socket read timeout -> keep the watch's watchdog alive
                try {
                    $writer.WriteLine($pingLine)
                    $writer.Flush()
                } catch {
                    break
                }
                continue
            }
            if ($null -eq $line) { break }
            $line = $line.Trim()
            if ($line.Length -eq 0) { continue }
            try {
                $m = $line | ConvertFrom-Json
            } catch {
                Write-Host "bad message: $line"
                continue
            }
            switch ($m.t) {
                'mouse' { Invoke-Mouse $m }
                'key'   { Invoke-Key $m }
                default { } # hello / ping
            }
        }
    } finally {
        # release any key held when the watch vanished
        foreach ($name in @($script:PressedKeys.Keys)) {
            $parts = $name -split ':'
            [WatchMouse.InputLib]::KeyScan([byte]$parts[1], ($parts[0] -eq '1'), $false)
        }
        $script:PressedKeys = @{}
        $script:PressedButtons = @{ L = $false; R = $false; M = $false }
        $reader.Dispose()
        $writer.Dispose()
        $Client.Close()
        Write-Host 'client disconnected'
    }
}

Write-Host("WatchMouse host listening on {0}:{1}" -f $Bind, $Port)
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse($Bind), $Port)
$listener.Start()
while ($true) {
    try {
        $client = $listener.AcceptTcpClient()
        Write-Host ('watch connected from {0}' -f $client.Client.RemoteEndPoint.Address)
        Handle-Client $client
    } catch {
        Write-Host ("listener error: {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 2
    }
}