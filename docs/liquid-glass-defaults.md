# Liquid Glass 默认值存档（设备实测固化）

> 采集时间：2026-08-20
> 设备：5ddfea15（debug 包 com.smartvision.gallery.debug）
> 数据源：`/data/data/com.smartvision.gallery.debug/files/datastore/liquid_glass_config.preferences_pb`（2513 bytes, `strings` 解析）
> 说明：以下为用户在 GlassPlayground 调好的值，`strings` 解码 protobuf CSV。小数位为滑杆拖动残留精度，原样保留。

## TabBar
```
cornerRadius=32.0, shadowElevation=1.1396973, blurRadius=3.0147204, lensAmount=32.0,
tintArgb=0xFFEAF4FF, tintAlpha=0.15, highlightAlpha=0.35, vibrancy=true,
specularAlpha=0.55, bottomShadowAlpha=0.07801767, edgeDarkAlpha=0.06835171, topTintExtra=0.45
```

## Static
```
cornerRadius=18.0, shadowElevation=6.0, blurRadius=2.9755275, lensAmount=20.072989,
tintArgb=0xFFF0F6FF, tintAlpha=0.12, highlightAlpha=0.35, vibrancy=true,
specularAlpha=0.45, bottomShadowAlpha=0.086947605, edgeDarkAlpha=0.08635917, topTintExtra=0.35
```

## TopBar
```
cornerRadius=0.0, shadowElevation=0.0, blurRadius=3.1104944, lensAmount=20.035328,
tintArgb=0xFFF2F7FF, tintAlpha=0.12, highlightAlpha=0.15, vibrancy=true,
specularAlpha=0.25, bottomShadowAlpha=0.05, edgeDarkAlpha=0.07968616, topTintExtra=0.25
```

## Control
```
cornerRadius=999.0, shadowElevation=4.0, blurRadius=2.997056, lensAmount=19.95682,
lensPressExtra=64.0, tintArgb=0xFFF0F4FF, tintAlpha=0.08, highlightAlpha=0.3, vibrancy=true,
specularAlpha=0.4, bottomShadowAlpha=0.101177245, edgeDarkAlpha=0.1, topTintExtra=0.3
```

## Toggle
```
width=51.0, height=31.0, trackCornerRadius=15.5, knobDiameter=27.0, knobShadowBlur=3.0,
onColorArgb=0xFF0088FF, offTrackAlpha=0.12, blurRadius=5.5395455
```

## SearchBar
```
cornerRadius=16.0, shadowElevation=1.0542965, blurRadius=3.0453568, lensAmount=19.981937,
tintArgb=0xFFF0F4FF, tintAlpha=0.1, highlightAlpha=0.3, vibrancy=true,
specularAlpha=0.35, bottomShadowAlpha=0.09921453, edgeDarkAlpha=0.1, topTintExtra=0.3
```

## ChipFilter
```
cornerRadius=30.297167, shadowElevation=4.0, blurRadius=3.0264966, lensAmount=20.745031,
tintArgb=0xFFF0F4FF, tintAlpha=0.08, highlightAlpha=0.3, vibrancy=true,
specularAlpha=0.4, bottomShadowAlpha=0.07154111, edgeDarkAlpha=0.1, topTintExtra=0.3,
springDampingRatio=0.2869336, springStiffness=100.0, selectedScale=1.0977943, floatingElevation=10.013742
```

## HeroFrost
```
blurRadius=20.424711, lensAmount=30.01058, vibrancy=true,
tintArgb=0xFFF0F4FF, tintAlpha=0.1, highlightAlpha=0.2,
fadeStart=0.3, fadeEnd=0.85,
specularAlpha=0.3, bottomShadowAlpha=0.11040235, edgeDarkAlpha=0.08, topTintExtra=0.25
```

## Lens
```
lensSize=100.0, lensRefractionHeight=14.004309, lensRefractionAmount=16.442223,
lensChromaticAberration=1.0, stretchMax=1.5, squashMax=0.0,
iconScaleInside=1.0, iconTintAlpha=1.0
```

## Background
```
blurRadius=48.0, lensAmount=0.0, vibrancy=true,
lightTintArgb=0xFFFFFFFF, lightTintAlpha=0.18,
darkTintArgb=0xFF1A1A2E, darkTintAlpha=0.4, highlightAlpha=0.08,
specularAlpha=0.2, bottomShadowAlpha=0.08390579, edgeDarkAlpha=0.08105948, topTintExtra=0.25
```

## Backdrop
未在设备上保存过条目，保持 dataclass 默认（`0xFFFFE4EC / 0xFFE8F4FF / 0xFFFFF8E8 / 0xFF2A1B2E / 0xFF1A2B3D / 0xFF2E2A1B`）。
