package com.example.nearchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nearchat.data.model.Message
import com.example.nearchat.ui.theme.BubbleMine
import com.example.nearchat.ui.theme.BubbleMineLight
import com.example.nearchat.ui.theme.BubbleTheirs
import com.example.nearchat.ui.theme.BubbleTheirsLight
import com.example.nearchat.util.formatTime
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (message.isMine) {
        BubbleMine
    } else {
        if (isDark) BubbleTheirs else BubbleTheirsLight
    }
    val textColor = if (message.isMine) {
        Color.White
    } else {
        if (isDark) Color.White else Color.Black
    }
    val timeColor = if (message.isMine) {
        Color.White.copy(alpha = 0.7f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)
    }

    val bubbleShape = if (message.isMine) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = formatTime(message.timestamp),
                    color = timeColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
