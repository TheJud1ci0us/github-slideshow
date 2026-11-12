package com.ascolta.app.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of text that gets smaller instead of wrapping letter by letter or being cut, for
 * narrow buttons and labels (large font sizes in the phone's settings, long translations).
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    minSize: TextUnit = 8.sp,
) {
    val c = if (color != Color.Unspecified) color else style.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    val maxSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 14.sp
    BasicText(
        text,
        modifier = modifier,
        style = style.merge(TextStyle(color = c, textAlign = textAlign ?: style.textAlign)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 0.5.sp),
    )
}
