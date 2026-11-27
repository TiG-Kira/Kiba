// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// Kiba 自有顶栏家族的阴影绘制内部件。取自 miuix-glass 0.9.4 快照的 shadow shader
// （官方 0.9.5-rc01 的等价实现是模块内部的 Modifier.Node，不对外公开，而 Kiba
// GlassIconButton 需要跟随折叠进度在**绘制期**插值阴影透明度），路径数据原样保留。

package com.kira.kiba.ui.material.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.RuntimeShader
import top.yukonga.miuix.kmp.material.asBrush
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import kotlin.math.min

/** 缩略 superellipse 拟合系数与相关常量（与 miuix 上游 GlassGeometry 一致）。 */
private const val TILE_SCALE = 1.5286649465560913f
private const val FIT_4 = -0.7391197269f
private const val FIT_3 = 2.4034927648f
private const val FIT_2 = -2.4907319173f
private const val FIT_1 = 0.4768708960f
private const val FIT_0 = 0.4747847594f
private const val MIN_SUPERCIRCLE_RADIUS = 0.5f

/**
 * 剪影距离场：材质 shader 与阴影 shader 共用同一 SDF，保证阴影和玻璃边缘永不错位。
 * 与 miuix 上游 GLASS_SDF_SOURCE 逐字一致（拟合系数已内联）。
 */
private val GLASS_SDF_SOURCE: String = """
uniform float2 in_size;         // component size
uniform float4 in_radii;        // topLeft, topRight, bottomRight, bottomLeft
uniform float in_smoothing;     // 0 = circular corner, 1 = continuous corner

float roundedBoxSdf(float2 p, float2 b, float r) {
    float2 d = p - b + r;
    return min(max(d.x, d.y), 0.0) + length(max(d, float2(0.0))) - r;
}

float supercircleSdf(float2 off, float tile) {
    float2 q = max(float2(0.0), (float2(tile) + off) / tile);
    float hi = max(q.x, q.y);
    float ratio = (hi == 0.0) ? 0.0 : clamp(min(q.x, q.y) / hi, 0.0, 1.0);
    float fit = (((((-0.7391197269 * ratio + 2.4034927648) * ratio + -2.4907319173) * ratio + 0.4768708960) * ratio + 0.4747847594));
    float len = length(q);
    float distBase = (len + 1.0) - 1.0 / (1.0 - ratio * ratio * clamp(len, 0.0, 1.0) * fit);
    return min(max(tile + off.x, tile + off.y), 0.0) + tile * (distBase - 1.0);
}

float pickRadius(float2 local, float2 b) {
    float top = (local.x < b.x) ? in_radii.x : in_radii.y;
    float bottom = (local.x < b.x) ? in_radii.w : in_radii.z;
    return min((local.y < b.y) ? top : bottom, min(b.x, b.y));
}

float sdfShape(float2 p, float2 b, float r) {
    float box = roundedBoxSdf(p, b, r);
    float minHalf = min(b.x, b.y);
    if (in_smoothing <= 0.001 || r < 0.5 || (minHalf - r) <= 1.0) {
        return box;
    }
    float tile = min(1.5286649465560913 * r, minHalf);
    return mix(box, supercircleSdf(p - b, tile), in_smoothing);
}

// Distance to the silhouette from a point in component space. Negative inside.
float silhouetteSdf(float2 local) {
    float2 b = in_size * 0.5;
    return sdfShape(abs(local - b), b, pickRadius(local, b));
}
"""

/** Cache key for the compiled shadow shader. */
private const val GLASS_SHADOW_SHADER_KEY = "KibaGlassShadow"

/** The shadow a surface casts, drawn from the same distance field as the surface itself. */
private val GLASS_SHADOW_SHADER: String = """
$GLASS_SDF_SOURCE
uniform float2 in_shadowOffset;  // displacement in pixels
uniform float2 in_shadowShape;   // reach, dispersion
uniform float4 in_shadowColor;   // rgb, strength

half4 main(float2 coord) {
    float d = silhouetteSdf(coord - in_shadowOffset);
    float reach = max(in_shadowShape.x, 0.5);
    float t = clamp(1.0 - d / reach, 0.0, 1.0);
    // Dispersion reshapes the falloff: below a half it hugs the edge, above it spreads and thins.
    float falloff = pow(t, 1.0 / max(in_shadowShape.y, 0.05));
    float a = falloff * in_shadowColor.a;
    return half4(half3(in_shadowColor.rgb * a), half(a));
}
"""

private val shadowShader: RuntimeShader? by lazy {
    if (isRuntimeShaderSupported()) RuntimeShader(GLASS_SHADOW_SHADER) else null
}

/** 方形填充在绘制期裁成玻璃形状的遮罩：rim 光晕需要溢出边界，不能 clip，只能 DstIn。 */
private val GLASS_MASK_SHADER: String = """
$GLASS_SDF_SOURCE
half4 main(float2 coord) {
    // One pixel of coverage either side of the boundary is all the anti-aliasing an edge needs.
    return half4(half(clamp(0.5 - silhouetteSdf(coord), 0.0, 1.0)));
}
"""

private val maskShader: RuntimeShader? by lazy {
    if (isRuntimeShaderSupported()) RuntimeShader(GLASS_MASK_SHADER) else null
}

/**
 * Trims the surface to [shape] at full resolution（移植自旧 vendored drawGlassMask）。
 * 必须在 Offscreen 合成层内调用（DstIn 只作用于本层内容）。
 */
internal fun DrawScope.drawGlassMask(shape: GlassShape, layoutDirection: LayoutDirection) {
    val shader = maskShader ?: return
    if (size.width <= 0f || size.height <= 0f) return
    shader.setSilhouetteUniforms(shape, layoutDirection, size, this)
    drawRect(brush = shader.asBrush(), blendMode = BlendMode.DstIn)
}

/** Uploads the size, corner radii and smoothing that the SDF source reads. */
private fun RuntimeShader.setSilhouetteUniforms(
    shape: GlassShape,
    layoutDirection: LayoutDirection,
    size: Size,
    density: Density,
) {
    val halfMin = min(size.width, size.height) * 0.5f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val start = shape.topStart.toPx(size, density)
    val end = shape.topEnd.toPx(size, density)
    val bottomEnd = shape.bottomEnd.toPx(size, density)
    val bottomStart = shape.bottomStart.toPx(size, density)

    setFloatUniform("in_size", size.width, size.height)
    setFloatUniform(
        "in_radii",
        (if (isLtr) start else end).coerceIn(0f, halfMin),
        (if (isLtr) end else start).coerceIn(0f, halfMin),
        (if (isLtr) bottomEnd else bottomStart).coerceIn(0f, halfMin),
        (if (isLtr) bottomStart else bottomEnd).coerceIn(0f, halfMin),
    )
    setFloatUniform("in_smoothing", shape.smoothing.coerceIn(0f, 1f))
}

/**
 * Draws the shadow a surface casts, reaching past its own bounds.
 *
 * @param shape The silhouette casting the shadow.
 * @param layoutDirection Resolves the shape's start and end corners.
 * @param shadow The shadow to cast.
 * @param alpha The surface opacity, so the shadow fades with the surface it belongs to.
 */
private fun DrawScope.drawGlassShadow(
    shape: GlassShape,
    layoutDirection: LayoutDirection,
    shadow: GlassShadow,
    alpha: Float,
) {
    val shader = shadowShader ?: return
    if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return

    val sourceScale = density / GlassDefaults.SourceDensity
    val reach = (shadow.radius * sourceScale).coerceAtLeast(1f)
    val offsetX = shadow.offsetX * sourceScale
    val offsetY = shadow.offsetY * sourceScale

    shader.setSilhouetteUniforms(shape, layoutDirection, size, this)
    shader.setFloatUniform("in_shadowOffset", offsetX, offsetY)
    shader.setFloatUniform("in_shadowShape", reach, shadow.dispersion)
    val color = shadow.color
    shader.setFloatUniform(
        "in_shadowColor",
        color.red,
        color.green,
        color.blue,
        color.alpha * alpha,
    )

    drawRect(
        brush = shader.asBrush(),
        topLeft = Offset(-reach + offsetX, -reach + offsetY),
        size = Size(size.width + reach * 2f, size.height + reach * 2f),
    )
}

/** Static-alpha 变体（与官方 miuix-glass 同名 API 等价）。 */
internal fun Modifier.glassShadow(
    shape: GlassShape,
    shadow: GlassShadow?,
    alpha: Float = 1f,
): Modifier = glassShadow(shape, shadow) { alpha }

/**
 * 绘制期透明度变体：alpha 在 draw 阶段读取（滚动折叠进度），不触发重组。
 * 官方 0.9.5-rc01 的同名实现是模块内部的，Kiba 顶栏家族自带一份。
 */
internal fun Modifier.glassShadow(
    shape: GlassShape,
    shadow: GlassShadow?,
    alpha: () -> Float,
): Modifier = if (shadow == null) {
    this
} else {
    this.drawBehind { drawGlassShadow(shape, layoutDirection, shadow, alpha()) }
}
