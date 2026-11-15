import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.lixing.components.ButtonVariant
import com.lixing.components.LixingButton
import com.lixing.components.LixingChoiceHost
import com.lixing.components.LixingSupportDevelopment
import com.lixing.components.LixingTheme

@Composable
fun SupportDevelopmentWindow(
    visible: Boolean,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        visible = visible,
        title = "支持开发",
        state = rememberWindowState(width = 560.dp, height = 520.dp),
    ) {
        LixingTheme {
            val colors = LixingTheme.colors
            val scrollState = rememberScrollState()
            LixingChoiceHost {
                Box(Modifier.fillMaxSize().background(colors.canvas)) {
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                "支持开发",
                                Modifier.weight(1f).semantics { heading() },
                                style = TextStyle(
                                    color = colors.text,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            LixingButton("关闭", onClose, variant = ButtonVariant.Quiet)
                        }
                        LixingSupportDevelopment(Modifier.fillMaxWidth())
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(14.dp)
                            .semantics { contentDescription = "支持内容滚动条" },
                    )
                }
            }
        }
    }
}
