package com.lixing.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.security.MessageDigest
import java.util.HexFormat

private fun resource(name: String): ByteArray =
    requireNotNull(LixingDesign::class.java.getResourceAsStream("/lixing-components/$name"))
        .use { it.readBytes() }

private fun ByteArray.sha256(): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

object LixingDesign {
    private val definitionBytes = resource("definitions.json")
    private val tokenBytes = resource("tokens.json")
    internal val tokens = JSONObject(tokenBytes.decodeToString())
    internal val definitions = JSONObject(definitionBytes.decodeToString())
    private val components = definitions.getJSONObject("components")
    val definitionSha256: String = definitionBytes.sha256()
    val tokensSha256: String = tokenBytes.sha256()
    val designVersion: String = definitions.getString("designVersion")

    init {
        check(designVersion == tokens.getJSONObject("meta").getString("version"))
    }

    internal val focusWidth = definitions.getJSONObject("focus").getInt("width").dp
    internal val focusOffset = definitions.getJSONObject("focus").getInt("offset").dp
    internal val focusRole = definitions.getJSONObject("focus").getString("role")
    internal val selectedFocusRole = definitions.getJSONObject("focus").getString("selectedRole")

    internal fun component(id: String) = ComponentDefinition(components.getJSONObject(id))
    fun space(level: Int): Dp = tokens.getJSONObject("space").getInt(level.toString()).dp

    fun textSize(role: String): TextUnit =
        tokens.getJSONObject("typography").getJSONObject("size").getInt(role).sp
}

internal class ComponentDefinition(private val json: JSONObject) {
    private val geometry = json.getJSONObject("geometry")
    private val states = json.getJSONObject("states")
    val interaction: JSONObject get() = json.getJSONObject("interaction")

    fun number(key: String): Float {
        val value = geometry.get(key)
        if (value is Number) return value.toFloat()
        val (group, role) = (value as String).split('.', limit = 2)
        return LixingDesign.tokens.getJSONObject(group).getDouble(role).toFloat()
    }

    fun dp(key: String): Dp = number(key).dp
    fun optionalDp(key: String, fallback: Dp = 0.dp): Dp =
        if (geometry.has(key)) dp(key) else fallback

    fun roles(state: String): JSONObject? = states.optJSONObject(state)
    fun variant(name: String): JSONObject? = json.optJSONObject("variants")?.optJSONObject(name)
}

@Immutable
class LixingColors internal constructor(private val roles: Map<String, Color>) {
    operator fun get(role: String): Color =
        if (role == "transparent") Color.Transparent else roles.getValue(role)

    val canvas get() = this["canvas"]
    val surface get() = this["surface"]
    val surfaceRaised get() = this["surfaceRaised"]
    val text get() = this["text"]
    val textMuted get() = this["textMuted"]
    val accent get() = this["accent"]
    val border get() = this["border"]

    companion object {
        internal fun fromTokens(dark: Boolean, overrides: Map<String, Color>): LixingColors {
            val source = LixingDesign.tokens.getJSONObject("color")
                .getJSONObject(if (dark) "dark" else "light")
            return LixingColors(source.keys().asSequence().associateWith { role ->
                val hex = source.getString(role).removePrefix("#")
                val argb = if (hex.length == 6) "FF$hex" else hex.takeLast(2) + hex.take(6)
                Color(argb.toLong(16).toInt())
            } + overrides)
        }
    }
}

private val LocalColors = compositionLocalOf { LixingColors.fromTokens(false, emptyMap()) }

object LixingTheme {
    val colors: LixingColors
        @Composable get() = LocalColors.current
}

@Composable
fun LixingTheme(
    dark: Boolean? = null,
    roleOverrides: Map<String, Color> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val useDark = dark ?: isSystemInDarkTheme()
    val colors = remember(useDark, roleOverrides) { LixingColors.fromTokens(useDark, roleOverrides) }
    CompositionLocalProvider(LocalColors provides colors, content = content)
}

internal fun textStyle(
    color: Color,
    size: TextUnit = LixingDesign.textSize("md"),
    weight: FontWeight = FontWeight.Normal,
    lineHeightRole: String = "normal",
): TextStyle = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = FontFamily.SansSerif,
    lineHeight = size * LixingDesign.tokens.getJSONObject("typography")
        .getJSONObject("lineHeight").getDouble(lineHeightRole).toFloat(),
)

@Composable
internal fun ControlText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    underlineOffsetEm: Float = 0f,
) {
    val style = textStyle(color, weight = weight, lineHeightRole = "tight")
    if (underlineOffsetEm == 0f) {
        BasicText(text, modifier = modifier, style = style)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(text, modifier.drawBehind {
        val result = layout ?: return@drawBehind
        val offset = style.fontSize.toPx() * underlineOffsetEm
        for (line in 0 until result.lineCount) {
            val y = result.getLineBaseline(line) + offset
            drawLine(color, Offset(result.getLineLeft(line), y), Offset(result.getLineRight(line), y), 1.dp.toPx())
        }
    }, style = style, onTextLayout = { layout = it })
}

@Composable
fun LixingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LixingTheme.colors.text,
    size: TextUnit = LixingDesign.textSize("md"),
    weight: FontWeight = FontWeight.Normal,
) {
    BasicText(text, modifier = modifier, style = textStyle(color, size, weight))
}

enum class Tone(internal val role: String) {
    Info("info"), Success("success"), Warning("warning"), Danger("danger"),
}
