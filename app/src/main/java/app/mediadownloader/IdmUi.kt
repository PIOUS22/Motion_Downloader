package app.mediadownloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Colours of the 1DM-style design. */
object Idm {
    val Purple = Color(0xFF5A32AE)
    val Purple2 = Color(0xFF7B52C4)
    val Tint = Color(0xFFE4DDF5)
    val Tab = Color(0xFFD5C9F0)
    val Accent = Color(0xFFF4C15D)
    val Orange = Color(0xFFE8662B)
    val OrangeDark = Color(0xFFC9571F)
    val Olive = Color(0xFF6B7F1E)
    val Text = Color(0xFF222222)
    val Grey = Color(0xFF555555)
}

/** White card dialog like the design: 16 dp from the sides, 4 dp corners, dark scrim from the dialog window. */
@Composable
fun IdmDialog(top: Dp = 40.dp, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = top, bottom = 16.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(4.dp)).background(Color.White, RoundedCornerShape(4.dp))
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                content = content,
            )
        }
    }
}

@Composable
fun IdmTitle(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF555555))

@Composable
fun IdmCheck(label: String, checked: Boolean, color: Color = Idm.Purple, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(22.dp).background(if (checked) color else Color.White, RoundedCornerShape(3.dp)).border(2.dp, color, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Ico(Ic.DCheck, 16.dp, Color.White) }
        Text(label, fontSize = 14.sp, color = Idm.Text)
    }
}

/** Text field with only a thin line under it, like the design. */
@Composable
fun IdmField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, size: Int = 14, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = size.sp, color = Color(0xFFAAAAAA))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = size.sp, color = Idm.Text), cursorBrush = SolidColor(Idm.Purple),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Color(0xFFCCCCCC)))
}

@Composable
fun IdmSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int) {
    androidx.compose.material3.Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Idm.Purple, activeTrackColor = Idm.Purple, inactiveTrackColor = Color(0xFFC9BCE6),
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        ),
    )
}

@Composable
fun IdmButtons(left: String?, onLeft: () -> Unit, onCancel: () -> Unit, cancelText: String = "CANCEL", rightText: String = "START", onRight: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (left != null) Text(left, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable(onClick = onLeft).padding(vertical = 8.dp))
        else Box {}
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(cancelText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable(onClick = onCancel).padding(vertical = 8.dp))
            Text(rightText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable(onClick = onRight).padding(vertical = 8.dp))
        }
    }
}

@Composable
fun StorageText() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t = storageLine(ctx)
    // "Storage: 94.01GB / 457.60GB, 20.5% free" -> orange numbers like the design
    val m = Regex("Storage: (\\S+) / (\\S+), (\\S+)% free").find(t)
    if (m == null) { Text(t, fontSize = 13.sp, color = Color(0xFF666666)); return }
    val s = androidx.compose.ui.text.buildAnnotatedString {
        append("Storage: ")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Idm.OrangeDark, fontWeight = FontWeight.Bold)); append(m.groupValues[1]); pop()
        append("/${m.groupValues[2]}, ")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Idm.OrangeDark, fontWeight = FontWeight.Bold)); append(m.groupValues[3] + "%"); pop()
        append(" free")
    }
    androidx.compose.material3.Text(s, fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 6.dp))
}
