package com.risktracker.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Silent brand intro shown on every cold app launch. The dashboard remains in
 * MainActivity and is launched only after this lightweight visual sequence completes.
 */
class IntroActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val animationsEnabled = runCatching {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)

        if (!animationsEnabled) {
            openDashboard()
            return
        }

        window.statusBarColor = android.graphics.Color.rgb(10, 17, 16)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 16)
        setContent {
            MaterialTheme {
                PremiumIntroScreen {
                    openDashboard()
                }
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

@Composable
private fun PremiumIntroScreen(onFinished: () -> Unit) {
    val logoScale = remember { Animatable(0.92f) }
    val logoAlpha = remember { Animatable(0f) }
    val chartProgress = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val background = Color(0xFF0A1110)
    val accent = Color(0xFF55D6A2)
    val muted = Color(0xFF9BAFA7)

    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, tween(durationMillis = 420))
        logoScale.animateTo(1f, tween(durationMillis = 520))
        chartProgress.animateTo(1f, tween(durationMillis = 620))
        taglineAlpha.animateTo(1f, tween(durationMillis = 320))
        delay(220)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                modifier = Modifier.size(116.dp).scale(logoScale.value)
            ) {
                val stroke = 3.2.dp.toPx()
                drawArc(
                    color = accent.copy(alpha = logoAlpha.value),
                    startAngle = -90f,
                    sweepAngle = 360f * chartProgress.value,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                val points = listOf(
                    androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.62f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.40f, size.height * 0.48f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.55f, size.height * 0.56f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.76f, size.height * 0.31f)
                )
                val innerProgress = chartProgress.value
                for (index in 0 until points.lastIndex) {
                    val segmentProgress = (innerProgress * points.lastIndex - index).coerceIn(0f, 1f)
                    if (segmentProgress > 0f) {
                        val start = points[index]
                        val end = points[index + 1]
                        val current = androidx.compose.ui.geometry.Offset(
                            start.x + (end.x - start.x) * segmentProgress,
                            start.y + (end.y - start.y) * segmentProgress
                        )
                        drawLine(
                            color = accent.copy(alpha = logoAlpha.value),
                            start = start,
                            end = current,
                            strokeWidth = 3.4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Restrained candlestick accents give the mark a market-data cue.
                val candleX = listOf(0.31f, 0.51f, 0.69f)
                val candleY = listOf(0.43f, 0.51f, 0.39f)
                candleX.forEachIndexed { index, x ->
                    val y = candleY[index]
                    val centerX = size.width * x
                    val centerY = size.height * y
                    drawLine(
                        color = Color(0xFFB8C9C1).copy(alpha = logoAlpha.value * innerProgress),
                        start = androidx.compose.ui.geometry.Offset(centerX, centerY - 9.dp.toPx()),
                        end = androidx.compose.ui.geometry.Offset(centerX, centerY + 9.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = "RISK TRACKER",
                color = Color(0xFFF2F7F4).copy(alpha = logoAlpha.value),
                fontSize = 21.sp,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Clarity. Discipline. Control.",
                color = muted.copy(alpha = taglineAlpha.value),
                fontSize = 13.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
