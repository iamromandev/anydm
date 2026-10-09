package dev.anydm.desktop.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.theme.LocalTokens

/** A label over a compact bordered text field; Enter calls [onSubmit]. */
@Composable
fun FormField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    tag: String,
    secret: Boolean = false,
    placeholder: String = "",
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = t.secondaryText)
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(t.content, RoundedCornerShape(6.dp))
                .border(1.dp, t.separator, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = t.secondaryText)
            BasicTextField(
                value,
                onValue,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = t.text),
                cursorBrush = SolidColor(t.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier =
                    Modifier.fillMaxWidth().testTag(tag).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}
