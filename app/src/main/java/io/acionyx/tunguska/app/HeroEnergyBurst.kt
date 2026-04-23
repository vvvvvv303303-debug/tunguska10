package io.acionyx.tunguska.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val Tau = (PI * 2.0).toFloat()

private val BurstIgnitionEasing = CubicBezierEasing(0.16f, 0.82f, 0.22f, 1f)
private val BurstBloomEasing = CubicBezierEasing(0.2f, 0.74f, 0.22f, 1f)
private val BurstDecayEasing = CubicBezierEasing(0.32f, 0f, 0.2f, 1f)
private val BurstRibbonEasing = CubicBezierEasing(0.24f, 0.78f, 0.18f, 1f)

@Immutable
private data class WispSpec(
    val startDegrees: Float,
    val sweepDegrees: Float,
    val radiusScale: Float,
    val thicknessScale: Float,
    val waveScale: Float,
    val rotationScale: Float,
    val delayFraction: Float,
    val lifeFraction: Float,
)

@Immutable
private data class StreakSpec(
    val angleDegrees: Float,
    val widthScale: Float,
    val lengthScale: Float,
    val originScale: Float,
    val delayFraction: Float,
    val lifeFraction: Float,
)

@Immutable
private data class ParticleOrbitSpec(
    val radiusScale: Float,
    val particleCount: Int,
    val turnsPerLoop: Float,
    val trailScale: Float,
    val baseSizeScale: Float,
)

/**
 * Premium radial energy burst for a centered circular hero.
 *
 * Rendering approach:
 * - a compact ignition flare opens from the center
 * - an irregular molten torus blooms outward
 * - curved wisps ride the torus edge with offset lifetimes
 * - restrained radial streaks support the plasma mass without turning into a particle demo
 */
@Composable
fun HeroEnergyBurst(
    accentColor: Color,
    trigger: Int,
    intensity: Float,
    durationMillis: Int,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(1f) }
    var hasMounted by remember { mutableStateOf(false) }
    val stableIntensity = intensity.coerceIn(0.2f, 1.3f)
    val wispSpecs = remember { heroWispSpecs() }
    val streakSpecs = remember { heroStreakSpecs() }

    LaunchedEffect(trigger, durationMillis) {
        if (!hasMounted) {
            hasMounted = true
            if (trigger == 0) return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMillis.coerceIn(420, 2200),
                easing = LinearEasing,
            ),
        )
    }

    val globalProgress = progress.value
    if (globalProgress >= 0.999f) {
        return
    }

    Canvas(modifier = modifier) {
        val center = center
        val iconRadius = size.minDimension * 0.23f
        val burstRadius = iconRadius * (0.86f + stableIntensity * 0.18f)
        val ignition = phaseWindow(globalProgress, 0f, 0.18f, BurstIgnitionEasing)
        val bloom = phaseWindow(globalProgress, 0.06f, 0.54f, BurstBloomEasing)
        val residual = phaseWindow(globalProgress, 0.34f, 1f, BurstBloomEasing)
        val fade = 1f - BurstDecayEasing.transform(globalProgress)
        val instability = accentInstability(accentColor)

        drawNebulaBase(
            accentColor = accentColor,
            center = center,
            burstRadius = burstRadius,
            ignition = ignition,
            bloom = bloom,
            fade = fade,
            intensity = stableIntensity,
        )

        drawIgnitionFlares(
            accentColor = accentColor,
            center = center,
            burstRadius = burstRadius,
            ignition = ignition,
            fade = fade,
            instability = instability,
        )

        drawMembraneShell(
            accentColor = accentColor,
            center = center,
            burstRadius = burstRadius,
            bloom = bloom,
            residual = residual,
            fade = fade,
            intensity = stableIntensity,
        )

        drawPlasmaTorus(
            accentColor = accentColor,
            center = center,
            burstRadius = burstRadius,
            bloom = bloom,
            residual = residual,
            fade = fade,
            intensity = stableIntensity,
            instability = instability,
        )

        wispSpecs.forEach { spec ->
            drawPlasmaWisp(
                spec = spec,
                accentColor = accentColor,
                center = center,
                burstRadius = burstRadius,
                globalProgress = globalProgress,
                fade = fade,
                intensity = stableIntensity,
                instability = instability,
            )
        }

        streakSpecs.forEach { spec ->
            drawBurstStreak(
                spec = spec,
                accentColor = accentColor,
                center = center,
                burstRadius = burstRadius,
                globalProgress = globalProgress,
                fade = fade,
                intensity = stableIntensity,
                instability = instability,
            )
        }
    }
}

@Composable
fun HeroEnergyBurstDemoScreen(
    modifier: Modifier = Modifier,
) {
    var trigger by remember { mutableIntStateOf(1) }
    val accent = Color(0xFF67FF83)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050B12))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                HeroEnergyBurst(
                    accentColor = accent,
                    trigger = trigger,
                    intensity = 1f,
                    durationMillis = 1120,
                    modifier = Modifier.fillMaxSize(),
                )
                DemoHeroShield(
                    accentColor = accent,
                    modifier = Modifier.size(166.dp),
                )
            }
            Text(
                text = "Hero Energy Burst",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFE6EEF2),
            )
            Text(
                text = "Centered plasma torus, curved wisps, and restrained ignition streaks.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF95A9B1),
            )
            Button(onClick = { trigger += 1 }) {
                Text("Replay Burst")
            }
        }
    }
}

@Composable
private fun DemoHeroShield(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF09141B),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shield = Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.14f)
                cubicTo(
                    size.width * 0.22f,
                    size.height * 0.2f,
                    size.width * 0.18f,
                    size.height * 0.38f,
                    size.width * 0.22f,
                    size.height * 0.6f,
                )
                cubicTo(
                    size.width * 0.26f,
                    size.height * 0.78f,
                    size.width * 0.42f,
                    size.height * 0.86f,
                    size.width * 0.5f,
                    size.height * 0.9f,
                )
                cubicTo(
                    size.width * 0.58f,
                    size.height * 0.86f,
                    size.width * 0.74f,
                    size.height * 0.78f,
                    size.width * 0.78f,
                    size.height * 0.6f,
                )
                cubicTo(
                    size.width * 0.82f,
                    size.height * 0.38f,
                    size.width * 0.78f,
                    size.height * 0.2f,
                    size.width * 0.5f,
                    size.height * 0.14f,
                )
                close()
            }
            drawPath(
                path = shield,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.16f),
                        accentColor.copy(alpha = 0.28f),
                    ),
                    start = Offset(size.width * 0.32f, size.height * 0.18f),
                    end = Offset(size.width * 0.68f, size.height * 0.88f),
                ),
            )
            drawPath(
                path = shield,
                color = accentColor,
                style = Stroke(
                    width = size.minDimension * 0.032f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            val tick = Path().apply {
                moveTo(size.width * 0.36f, size.height * 0.54f)
                lineTo(size.width * 0.47f, size.height * 0.66f)
                lineTo(size.width * 0.67f, size.height * 0.42f)
            }
            drawPath(
                path = tick,
                color = accentColor,
                style = Stroke(
                    width = size.minDimension * 0.065f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private fun DrawScope.drawNebulaBase(
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    ignition: Float,
    bloom: Float,
    fade: Float,
    intensity: Float,
) {
    val outerRadius = burstRadius * (1.18f + bloom * 0.42f + intensity * 0.06f)
    val innerRadius = burstRadius * (0.42f + ignition * 0.18f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.16f * fade),
                accentColor.copy(alpha = 0.08f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = outerRadius * 1.18f,
        ),
        center = center,
        radius = outerRadius,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.1f * fade),
                accentColor.copy(alpha = 0.04f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = innerRadius * 1.8f,
        ),
        center = center,
        radius = innerRadius,
    )
}

private fun DrawScope.drawIgnitionFlares(
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    ignition: Float,
    fade: Float,
    instability: Float,
) {
    if (ignition <= 0f) return

    val beamAngles = listOf(24f, 112f, 208f, 292f)
    beamAngles.forEachIndexed { index, angle ->
        val angleWithDrift = angle + instability * if (index % 2 == 0) 5.5f else -4.5f
        val direction = angleUnit(angleWithDrift)
        val tangent = perpendicular(direction)
        val length = burstRadius * (1.6f + index * 0.12f + ignition * 1.08f)
        val width = burstRadius * (0.036f + (index % 2) * 0.008f)
        val root = center + tangent.scaled(width * 0.12f * if (index % 2 == 0) 1f else -1f)
        val tip = root + direction.scaled(length)
        val path = Path().apply {
            moveTo(root.x - tangent.x * width, root.y - tangent.y * width)
            cubicTo(
                root.x + direction.x * length * 0.14f - tangent.x * width * 0.3f,
                root.y + direction.y * length * 0.14f - tangent.y * width * 0.3f,
                root.x + direction.x * length * 0.72f - tangent.x * width * 0.08f,
                root.y + direction.y * length * 0.72f - tangent.y * width * 0.08f,
                tip.x,
                tip.y,
            )
            cubicTo(
                root.x + direction.x * length * 0.72f + tangent.x * width * 0.08f,
                root.y + direction.y * length * 0.72f + tangent.y * width * 0.08f,
                root.x + direction.x * length * 0.14f + tangent.x * width * 0.3f,
                root.y + direction.y * length * 0.14f + tangent.y * width * 0.3f,
                root.x + tangent.x * width,
                root.y + tangent.y * width,
            )
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.1f * fade * ignition),
                    accentColor.copy(alpha = 0.03f * fade * ignition),
                    Color.Transparent,
                ),
                start = root,
                end = tip,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawMembraneShell(
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    bloom: Float,
    residual: Float,
    fade: Float,
    intensity: Float,
) {
    val membrane = phaseWindow((bloom * 0.78f + residual * 0.22f).coerceIn(0f, 1f), 0f, 1f, BurstBloomEasing)
    if (membrane <= 0.01f) return

    val radius = burstRadius * (0.92f + membrane * 0.9f + intensity * 0.08f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0f),
                accentColor.copy(alpha = 0.05f * fade * membrane),
                accentColor.copy(alpha = 0.09f * fade * membrane),
                accentColor.copy(alpha = 0.02f * fade * membrane),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 1.1f,
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        color = accentColor.copy(alpha = 0.1f * fade * membrane),
        radius = radius,
        center = center,
        style = Stroke(width = burstRadius * 0.032f),
    )
}

private fun DrawScope.drawPlasmaTorus(
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    bloom: Float,
    residual: Float,
    fade: Float,
    intensity: Float,
    instability: Float,
) {
    val morph = bloom * 0.74f + residual * 0.34f
    val outerPath = ArrayList<Offset>(72)
    val innerPath = ArrayList<Offset>(72)
    val ringPath = Path().apply { fillType = PathFillType.EvenOdd }

    for (index in 0 until 72) {
        val t = index / 71f
        val theta = t * Tau
        val lobe2 = sin(theta * 2f - morph * Tau * 0.18f + 0.6f) * 0.08f
        val lobe4 = sin(theta * 4f + morph * Tau * 0.3f + 1.4f) * 0.052f
        val lobe7 = cos(theta * 7f - morph * Tau * 0.46f + instability * 2.2f) * 0.024f
        val asymmetry = sin(theta - morph * Tau * 0.12f - 0.9f) * 0.068f
        val turbulence = harmonicNoise(theta, morph, instability) * 0.032f
        val outerRadius = burstRadius * (0.86f + lobe2 + lobe4 + lobe7 + asymmetry + turbulence)
        val innerRadius = burstRadius * (
            0.51f +
                sin(theta * 2f + morph * Tau * 0.16f + 0.2f) * 0.042f +
                cos(theta * 6f - morph * Tau * 0.34f + 1.1f) * 0.016f
            )
        outerPath += center + polar(theta, outerRadius)
        innerPath += center + polar(theta, innerRadius)
    }

    ringPath.moveTo(outerPath.first().x, outerPath.first().y)
    outerPath.drop(1).forEach { point -> ringPath.lineTo(point.x, point.y) }
    ringPath.close()
    ringPath.moveTo(innerPath.last().x, innerPath.last().y)
    innerPath.asReversed().drop(1).forEach { point -> ringPath.lineTo(point.x, point.y) }
    ringPath.close()

    drawPath(
        path = ringPath,
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.04f * fade),
                accentColor.copy(alpha = 0.16f * fade),
                accentColor.copy(alpha = 0.08f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = burstRadius * 1.3f,
        ),
    )

    drawPath(
        path = ringPath,
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.16f * fade),
                accentColor.copy(alpha = 0.07f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = burstRadius * 1.44f,
        ),
        blendMode = BlendMode.Plus,
    )

    val seamSpecs = listOf(
        Triple(214f, 78f, 0.9f),
        Triple(302f, 64f, 0.94f),
        Triple(102f, 56f, 0.84f),
        Triple(18f, 42f, 0.8f),
    )
    seamSpecs.forEachIndexed { seamIndex, (start, sweep, radiusScale) ->
        val seamPath = buildSeamPath(
            center = center,
            baseRadius = burstRadius * radiusScale,
            startDegrees = start + instability * seamIndex * 4f,
            sweepDegrees = sweep,
            morph = morph,
            seed = seamIndex * 1.7f,
        )
        drawPath(
            path = seamPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0f),
                    accentColor.copy(alpha = 0.22f * fade),
                    accentColor.copy(alpha = 0.1f * fade),
                    accentColor.copy(alpha = 0f),
                ),
                start = center + polar((start / 180f * PI).toFloat(), burstRadius),
                end = center + polar(((start + sweep) / 180f * PI).toFloat(), burstRadius),
            ),
            style = Stroke(
                width = burstRadius * (0.074f + seamIndex * 0.006f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawPlasmaWisp(
    spec: WispSpec,
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    globalProgress: Float,
    fade: Float,
    intensity: Float,
    instability: Float,
) {
    val local = windowedProgress(globalProgress, spec.delayFraction, spec.lifeFraction) ?: return
    val reveal = BurstRibbonEasing.transform(local)
    val decay = 1f - BurstDecayEasing.transform(local)
    val sweep = spec.sweepDegrees * (0.62f + reveal * 0.42f)
    val rotation = reveal * spec.rotationScale * 16f + instability * 6f
    val path = buildSeamPath(
        center = center,
        baseRadius = burstRadius * spec.radiusScale,
        startDegrees = spec.startDegrees + rotation,
        sweepDegrees = sweep,
        morph = reveal * 0.9f + instability * 0.1f,
        seed = spec.waveScale * 11f,
    )
    val glowWidth = burstRadius * spec.thicknessScale * (1.24f + intensity * 0.08f)
    val coreWidth = glowWidth * 0.42f

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0f),
                accentColor.copy(alpha = 0.12f * decay * fade),
                accentColor.copy(alpha = 0.18f * decay * fade),
                accentColor.copy(alpha = 0f),
            ),
            start = center - Offset(burstRadius, burstRadius * 0.42f),
            end = center + Offset(burstRadius, burstRadius * 0.42f),
        ),
        style = Stroke(width = glowWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0f),
                accentColor.copy(alpha = 0.28f * decay * fade),
                accentColor.copy(alpha = 0.1f * decay * fade),
                accentColor.copy(alpha = 0f),
            ),
            start = center - Offset(burstRadius * 0.48f, burstRadius * 0.14f),
            end = center + Offset(burstRadius * 0.48f, burstRadius * 0.14f),
        ),
        style = Stroke(width = coreWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus,
    )
}

private fun DrawScope.drawBurstStreak(
    spec: StreakSpec,
    accentColor: Color,
    center: Offset,
    burstRadius: Float,
    globalProgress: Float,
    fade: Float,
    intensity: Float,
    instability: Float,
) {
    val local = windowedProgress(globalProgress, spec.delayFraction, spec.lifeFraction) ?: return
    val reveal = BurstIgnitionEasing.transform(local)
    val decay = 1f - BurstDecayEasing.transform(local)
    val angle = spec.angleDegrees + instability * sin((local + spec.angleDegrees / 360f) * Tau) * 8f
    val direction = angleUnit(angle)
    val origin = center + direction.scaled(burstRadius * spec.originScale)
    val tip = origin + direction.scaled(burstRadius * spec.lengthScale * (0.4f + reveal * 0.8f))
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.14f * decay * fade),
                accentColor.copy(alpha = 0.04f * decay * fade),
                Color.Transparent,
            ),
            start = origin,
            end = tip,
        ),
        start = origin,
        end = tip,
        strokeWidth = burstRadius * spec.widthScale * (0.84f + intensity * 0.1f),
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus,
    )
}

private fun heroWispSpecs(): List<WispSpec> = listOf(
    WispSpec(startDegrees = 206f, sweepDegrees = 92f, radiusScale = 0.98f, thicknessScale = 0.1f, waveScale = 0.22f, rotationScale = 1.2f, delayFraction = 0.06f, lifeFraction = 0.58f),
    WispSpec(startDegrees = 256f, sweepDegrees = 74f, radiusScale = 0.88f, thicknessScale = 0.082f, waveScale = 0.18f, rotationScale = 0.9f, delayFraction = 0.1f, lifeFraction = 0.54f),
    WispSpec(startDegrees = 302f, sweepDegrees = 84f, radiusScale = 0.95f, thicknessScale = 0.088f, waveScale = 0.2f, rotationScale = 1.08f, delayFraction = 0.14f, lifeFraction = 0.56f),
    WispSpec(startDegrees = 108f, sweepDegrees = 62f, radiusScale = 0.82f, thicknessScale = 0.072f, waveScale = 0.16f, rotationScale = 0.72f, delayFraction = 0.18f, lifeFraction = 0.5f),
)

private fun heroStreakSpecs(): List<StreakSpec> = listOf(
    StreakSpec(angleDegrees = 18f, widthScale = 0.022f, lengthScale = 0.84f, originScale = 0.94f, delayFraction = 0.08f, lifeFraction = 0.38f),
    StreakSpec(angleDegrees = 54f, widthScale = 0.018f, lengthScale = 0.8f, originScale = 0.96f, delayFraction = 0.12f, lifeFraction = 0.34f),
    StreakSpec(angleDegrees = 116f, widthScale = 0.02f, lengthScale = 0.76f, originScale = 0.92f, delayFraction = 0.1f, lifeFraction = 0.34f),
    StreakSpec(angleDegrees = 168f, widthScale = 0.018f, lengthScale = 0.8f, originScale = 0.95f, delayFraction = 0.14f, lifeFraction = 0.36f),
    StreakSpec(angleDegrees = 226f, widthScale = 0.02f, lengthScale = 0.72f, originScale = 0.92f, delayFraction = 0.12f, lifeFraction = 0.34f),
    StreakSpec(angleDegrees = 284f, widthScale = 0.018f, lengthScale = 0.74f, originScale = 0.94f, delayFraction = 0.14f, lifeFraction = 0.36f),
    StreakSpec(angleDegrees = 324f, widthScale = 0.02f, lengthScale = 0.78f, originScale = 0.96f, delayFraction = 0.1f, lifeFraction = 0.34f),
)

internal fun DrawScope.drawHeroPlasmaField(
    accentColor: Color,
    center: Offset,
    fieldRadius: Float,
    rotationDegrees: Float,
    energy: Float,
    pulse: Float,
    instability: Float = 0f,
    alphaScale: Float = 1f,
    deformationScale: Float = 1f,
    centerVoidScale: Float = 1f,
) {
    val outerPath = ArrayList<Offset>(80)
    val innerPath = ArrayList<Offset>(80)
    val ringPath = Path().apply { fillType = PathFillType.EvenOdd }
    val spin = (rotationDegrees / 180f * PI).toFloat()
    val clampedEnergy = energy.coerceIn(0.2f, 1.35f)
    val clampedAlphaScale = alphaScale.coerceIn(0.18f, 1.4f)
    val clampedDeformationScale = deformationScale.coerceIn(0.45f, 1.35f)
    val clampedCenterVoidScale = centerVoidScale.coerceIn(1f, 1.28f)

    for (index in 0 until 80) {
        val t = index / 79f
        val theta = t * Tau + spin
        val lobe2 = sin(theta * 2f - spin * 0.28f + 0.4f) * 0.072f * clampedDeformationScale
        val lobe4 = sin(theta * 4f + spin * 0.46f + 1.1f) * 0.046f * clampedDeformationScale
        val lobe7 = cos(theta * 7f - spin * 0.74f + 2.2f) * 0.018f * clampedDeformationScale
        val asymmetry = sin(theta - spin * 0.18f - 0.84f) * 0.058f * clampedDeformationScale
        val turbulence = harmonicNoise(theta, spin * 0.08f, instability + clampedEnergy) * 0.024f * clampedDeformationScale
        val outerRadius = fieldRadius * (0.9f + lobe2 + lobe4 + lobe7 + asymmetry + turbulence)
        val innerRadius = fieldRadius * (
            0.57f * clampedCenterVoidScale +
                sin(theta * 2f + spin * 0.26f) * 0.032f * clampedDeformationScale +
                cos(theta * 6f - spin * 0.42f + 1.5f) * 0.013f * clampedDeformationScale
            )
        outerPath += center + polar(theta, outerRadius)
        innerPath += center + polar(theta, innerRadius)
    }

    ringPath.moveTo(outerPath.first().x, outerPath.first().y)
    outerPath.drop(1).forEach { point -> ringPath.lineTo(point.x, point.y) }
    ringPath.close()
    ringPath.moveTo(innerPath.last().x, innerPath.last().y)
    innerPath.asReversed().drop(1).forEach { point -> ringPath.lineTo(point.x, point.y) }
    ringPath.close()

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.16f * pulse * clampedEnergy * clampedAlphaScale),
                accentColor.copy(alpha = 0.06f * pulse * clampedEnergy * clampedAlphaScale),
                Color.Transparent,
            ),
            center = center,
            radius = fieldRadius * 1.38f,
        ),
        center = center,
        radius = fieldRadius * 1.1f,
        blendMode = BlendMode.Plus,
    )

    drawPath(
        path = ringPath,
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.05f * clampedEnergy * clampedAlphaScale),
                accentColor.copy(alpha = 0.16f * clampedEnergy * clampedAlphaScale),
                accentColor.copy(alpha = 0.08f * clampedEnergy * clampedAlphaScale),
                Color.Transparent,
            ),
            center = center,
            radius = fieldRadius * 1.26f,
        ),
    )

    drawPath(
        path = ringPath,
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.12f * clampedEnergy * clampedAlphaScale),
                accentColor.copy(alpha = 0.05f * clampedEnergy * clampedAlphaScale),
                Color.Transparent,
            ),
            center = center,
            radius = fieldRadius * 1.4f,
        ),
        blendMode = BlendMode.Plus,
    )

    listOf(
        Triple(rotationDegrees + 208f, 78f, 0.92f),
        Triple(rotationDegrees + 298f, 62f, 0.88f),
        Triple(rotationDegrees + 96f, 54f, 0.82f),
    ).forEachIndexed { seamIndex, (start, sweep, radiusScale) ->
        val seamPath = buildSeamPath(
            center = center,
            baseRadius = fieldRadius * radiusScale,
            startDegrees = start,
            sweepDegrees = sweep,
            morph = pulse * 0.6f + clampedEnergy * 0.1f,
            seed = seamIndex * 1.4f + instability,
        )
        drawPath(
            path = seamPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0f),
                    accentColor.copy(alpha = 0.16f * clampedEnergy * clampedAlphaScale),
                    accentColor.copy(alpha = 0.07f * clampedEnergy * clampedAlphaScale),
                    accentColor.copy(alpha = 0f),
                ),
                start = center + polar((start / 180f * PI).toFloat(), fieldRadius),
                end = center + polar(((start + sweep) / 180f * PI).toFloat(), fieldRadius),
            ),
            style = Stroke(
                width = fieldRadius * (0.085f - seamIndex * 0.008f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Plus,
        )
    }

    drawCircle(
        color = accentColor.copy(alpha = 0.08f * clampedEnergy * clampedAlphaScale),
        radius = fieldRadius * 0.72f,
        center = center,
        style = Stroke(width = fieldRadius * 0.03f),
    )
}

internal fun DrawScope.drawHeroParticleFlow(
    accentColor: Color,
    center: Offset,
    fieldRadius: Float,
    rotationDegrees: Float,
    energy: Float,
    pulse: Float,
    instability: Float = 0f,
) {
    val clampedEnergy = energy.coerceIn(0.18f, 1.2f)
    val spin = (rotationDegrees / 180f * PI).toFloat()
    val bloomStrength = (0.34f + pulse * 0.66f).coerceIn(0.3f, 1.1f)
    val orbitSpecs = listOf(
        ParticleOrbitSpec(radiusScale = 0.42f, particleCount = 14, turnsPerLoop = 1f, trailScale = 0.068f, baseSizeScale = 0.014f),
        ParticleOrbitSpec(radiusScale = 0.6f, particleCount = 20, turnsPerLoop = -1f, trailScale = 0.082f, baseSizeScale = 0.016f),
        ParticleOrbitSpec(radiusScale = 0.8f, particleCount = 28, turnsPerLoop = 2f, trailScale = 0.096f, baseSizeScale = 0.018f),
        ParticleOrbitSpec(radiusScale = 1.02f, particleCount = 36, turnsPerLoop = -2f, trailScale = 0.112f, baseSizeScale = 0.021f),
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.11f * clampedEnergy * bloomStrength),
                accentColor.copy(alpha = 0.042f * clampedEnergy * bloomStrength),
                Color.Transparent,
            ),
            center = center,
            radius = fieldRadius * 1.62f,
        ),
        center = center,
        radius = fieldRadius * 1.24f,
        blendMode = BlendMode.Plus,
    )

    orbitSpecs.forEachIndexed { orbitIndex, spec ->
        val orbitRadius = fieldRadius * spec.radiusScale
        val orbitWeight = 0.82f + orbitIndex * 0.12f
        val directionSign = if (spec.turnsPerLoop < 0f) -1f else 1f
        for (particleIndex in 0 until spec.particleCount) {
            val fraction = particleIndex / spec.particleCount.toFloat()
            val seed = fraction * 37.2f + orbitIndex * 9.1f
            val sizeSeed = (((sin(seed * 1.7f) + cos(seed * 3.1f + 0.6f)) * 0.25f) + 0.5f).coerceIn(0f, 1f)
            val phaseSeed = seed * 0.73f + orbitIndex * 0.41f
            val theta = spin * spec.turnsPerLoop + fraction * Tau + orbitIndex * 0.74f + sizeSeed * 0.28f
            val drift = harmonicNoise(
                theta = theta,
                morph = pulse * 0.52f + orbitIndex * 0.18f,
                seed = instability + phaseSeed,
            )
            val angle = theta + drift * 0.14f
            val radius = orbitRadius * (1f + drift * 0.038f + (sizeSeed - 0.5f) * 0.05f)
            val point = center + polar(angle, radius)
            val tangent = perpendicular(polar(angle, 1f)).scaled(directionSign)
            val twinklePhase =
                spin * (abs(spec.turnsPerLoop) * 1.28f + 0.56f + sizeSeed * 0.82f) +
                    phaseSeed * Tau +
                    pulse * Tau * (0.92f + orbitIndex * 0.2f)
            val twinkle = ((sin(twinklePhase) + 1f) * 0.5f).coerceIn(0f, 1f)
            val shimmer = ((cos(twinklePhase * 0.62f + 0.9f) + 1f) * 0.5f).coerceIn(0f, 1f)
            val shine = (twinkle * 0.78f + shimmer * 0.22f).coerceIn(0f, 1f)
            val brightness = (0.2f + shine * 1.26f) * orbitWeight * (0.64f + sizeSeed * 0.78f)
            val particleSize = fieldRadius * spec.baseSizeScale * (0.58f + sizeSeed * 1.9f)
            val trailLength = fieldRadius * spec.trailScale * (0.76f + sizeSeed * 0.58f + shine * 0.82f)
            val glintLength = particleSize * (0.9f + shine * 1.3f)
            val tailStart = point - tangent.scaled(trailLength)

            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        accentColor.copy(alpha = 0.1f * clampedEnergy * brightness),
                        accentColor.copy(alpha = 0.02f * clampedEnergy * brightness),
                    ),
                    start = tailStart,
                    end = point,
                ),
                start = tailStart,
                end = point,
                strokeWidth = particleSize * 1.24f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )

            if (shine > 0.52f) {
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.18f * clampedEnergy * shine),
                            Color.White.copy(alpha = 0.22f * clampedEnergy * shine),
                            Color.Transparent,
                        ),
                        start = point,
                        end = point + tangent.scaled(glintLength),
                    ),
                    start = point,
                    end = point + tangent.scaled(glintLength),
                    strokeWidth = particleSize * 0.84f,
                    cap = StrokeCap.Round,
                    blendMode = BlendMode.Plus,
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.34f * clampedEnergy * brightness),
                        accentColor.copy(alpha = 0.12f * clampedEnergy * brightness),
                        Color.Transparent,
                    ),
                    center = point,
                    radius = particleSize * 4.4f,
                ),
                center = point,
                radius = particleSize * 1.9f,
                blendMode = BlendMode.Plus,
            )

            drawCircle(
                color = accentColor.copy(alpha = 0.54f * clampedEnergy * brightness),
                radius = particleSize * 0.56f,
                center = point,
                blendMode = BlendMode.Plus,
            )

            if (shine > 0.68f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.18f * clampedEnergy * shine),
                    radius = particleSize * 0.24f,
                    center = point + tangent.scaled(particleSize * 0.18f),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}

private fun buildSeamPath(
    center: Offset,
    baseRadius: Float,
    startDegrees: Float,
    sweepDegrees: Float,
    morph: Float,
    seed: Float,
): Path {
    val path = Path()
    val sampleCount = 18
    for (sample in 0 until sampleCount) {
        val t = sample / (sampleCount - 1f)
        val theta = ((startDegrees + sweepDegrees * t) / 180f * PI).toFloat()
        val noise = harmonicNoise(theta, morph + t * 0.18f, seed)
        val radius = baseRadius + baseRadius * 0.07f * noise
        val point = center + polar(theta, radius)
        if (sample == 0) {
            path.moveTo(point.x, point.y)
        } else {
            path.lineTo(point.x, point.y)
        }
    }
    return path
}

private fun harmonicNoise(theta: Float, morph: Float, seed: Float): Float {
    val a = sin(theta * 2f + morph * Tau * 0.26f + seed * 0.8f)
    val b = sin(theta * 4f - morph * Tau * 0.42f + seed * 1.5f) * 0.46f
    val c = cos(theta * 7f + morph * Tau * 0.18f + seed * 2.2f) * 0.24f
    return (a + b + c).coerceIn(-1.4f, 1.4f)
}

private fun phaseWindow(
    progress: Float,
    start: Float,
    end: Float,
    easing: Easing,
): Float {
    val local = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return easing.transform(local)
}

private fun windowedProgress(
    progress: Float,
    delayFraction: Float,
    lifeFraction: Float,
): Float? {
    val local = (progress - delayFraction) / lifeFraction
    return local.takeIf { it in 0f..1f }
}

private fun accentInstability(accentColor: Color): Float {
    val redDominance = accentColor.red - max(accentColor.green, accentColor.blue)
    return (redDominance * 1.24f).coerceIn(0f, 0.42f)
}

private fun angleUnit(angleDegrees: Float): Offset {
    val radians = (angleDegrees / 180f * PI).toFloat()
    return Offset(
        x = cos(radians.toDouble()).toFloat(),
        y = sin(radians.toDouble()).toFloat(),
    )
}

private fun polar(theta: Float, radius: Float): Offset = Offset(
    x = cos(theta.toDouble()).toFloat() * radius,
    y = sin(theta.toDouble()).toFloat() * radius,
)

private fun perpendicular(offset: Offset): Offset = Offset(-offset.y, offset.x)

private fun Offset.scaled(scale: Float): Offset = Offset(x = x * scale, y = y * scale)
