Add-Type -AssemblyName System.Drawing

function New-RoundedRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-StarPath([float]$cx, [float]$cy, [float]$outer, [float]$inner) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $points = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
    for ($i = 0; $i -lt 8; $i++) {
        $radius = if ($i % 2 -eq 0) { $outer } else { $inner }
        $angle = [Math]::PI * $i / 4 - [Math]::PI / 2
        $points.Add([System.Drawing.PointF]::new(
            $cx + [float]($radius * [Math]::Cos($angle)),
            $cy + [float]($radius * [Math]::Sin($angle))))
    }
    $path.AddPolygon($points.ToArray())
    return $path
}

function Draw-Icon([int]$size, [string]$output) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.Color]::FromArgb(255, 0x7C, 0x63, 0xF0),
        [System.Drawing.Color]::FromArgb(255, 0x36, 0x27, 0x7E),
        45.0)
    $g.FillRectangle($brush, $rect)

    $s = $size / 512.0

    $tab = New-RoundedRect (118 * $s) (176 * $s) (120 * $s) (70 * $s) (22 * $s)
    $body = New-RoundedRect (108 * $s) (206 * $s) (296 * $s) (176 * $s) (30 * $s)
    $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $g.FillPath($white, $tab)
    $g.FillPath($white, $body)

    $star = New-StarPath (330 * $s) (292 * $s) (66 * $s) (18 * $s)
    $gold = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0xFF, 0xC2, 0x4B))
    $g.FillPath($gold, $star)
    $star2 = New-StarPath (272 * $s) (248 * $s) (26 * $s) (7 * $s)
    $g.FillPath($gold, $star2)

    $g.Dispose()
    $bmp.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $output"
}

function Draw-FeatureGraphic([string]$output) {
    $w = 1024; $h = 500
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.Color]::FromArgb(255, 0x20, 0x18, 0x53),
        [System.Drawing.Color]::FromArgb(255, 0x5B, 0x4B, 0xD6),
        20.0)
    $g.FillRectangle($bg, $rect)

    $glow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(38, 0xFF, 0xFF, 0xFF))
    $g.FillEllipse($glow, 700, -120, 520, 520)

    $tab = New-RoundedRect 92 176 118 68 20
    $body = New-RoundedRect 82 206 292 174 30
    $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $g.FillPath($white, $tab)
    $g.FillPath($white, $body)
    $gold = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0xFF, 0xC2, 0x4B))
    $g.FillPath($gold, (New-StarPath 304 292 64 17))
    $g.FillPath($gold, (New-StarPath 246 248 25 7))

    $titleFont = New-Object System.Drawing.Font('Segoe UI', 58, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $subFont = New-Object System.Drawing.Font('Segoe UI', 26, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString('FileFlow', $titleFont, $white, 428, 168)
    $soft = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(230, 0xDD, 0xD8, 0xFF))
    $g.DrawString('Smart file manager with Auto Rules', $subFont, $soft, 432, 246)

    $g.Dispose()
    $bmp.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $output"
}

New-Item -ItemType Directory -Force -Path 'docs\play' | Out-Null
Draw-Icon 512 'docs\play\play-icon-512.png'
Draw-Icon 1024 'docs\play\play-icon-1024.png'
Draw-FeatureGraphic 'docs\play\feature-graphic-1024x500.png'
