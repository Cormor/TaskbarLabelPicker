package com.lixing.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image as SkiaImage

private data class PaymentCode(val asset: String, val name: String, val amount: String)

private val paymentCodes = listOf(
    PaymentCode("alipay", "支付宝", "¥5.98"),
    PaymentCode("wechat", "微信", "建议 ¥6"),
)

private val invertedCode = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
private fun rememberPaymentImage(name: String) = remember(name) {
    val bytes = requireNotNull(PaymentCode::class.java.getResourceAsStream(
        "/lixing-components/support/$name.png",
    )).use { it.readBytes() }
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LixingSupportDevelopment(modifier: Modifier = Modifier) {
    val colors = LixingTheme.colors
    val dark = colors.canvas.luminance() < .5f
    var standardScan by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LixingDesign.space(3))) {
        BasicText("使用对应应用扫码，自愿支持开发。", style = textStyle(colors.textMuted))
        if (dark) LixingToggleButton("标准扫码", standardScan, { standardScan = it })
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LixingDesign.space(3)),
            verticalArrangement = Arrangement.spacedBy(LixingDesign.space(3)),
        ) {
            for (payment in paymentCodes) PaymentCard(payment, dark && !standardScan)
        }
    }
}

@Composable
private fun PaymentCard(payment: PaymentCode, invert: Boolean) {
    val colors = LixingTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.border(1.dp, colors.border, shape).background(colors.surface, shape)
            .padding(LixingDesign.space(3)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LixingDesign.space(2)),
    ) {
        BasicText(payment.name, style = textStyle(colors.text, weight = FontWeight.SemiBold))
        BasicText(payment.amount, style = textStyle(colors.textMuted))
        Box(Modifier.background(if (invert) Color.Black else Color.White).padding(24.dp).size(168.dp)) {
            Image(
                rememberPaymentImage("${payment.asset}-code"), "${payment.name}支持开发扫码",
                Modifier.matchParentSize(), colorFilter = invertedCode.takeIf { invert },
                filterQuality = FilterQuality.None,
            )
            Image(
                rememberPaymentImage("${payment.asset}-marks"), null, Modifier.matchParentSize(),
                filterQuality = FilterQuality.None,
            )
        }
    }
}
