package com.anb.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF0B100E)
private val SurfaceDark = Color(0xFF151D19)
private val Green = Color(0xFF52E59A)
private val TextPrimary = Color(0xFFF1F5F2)
private val TextMuted = Color(0xFFA0ADA5)

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green,
                    background = Background,
                    surface = SurfaceDark,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                ANBHome()
            }
        }
    }
}

@Composable
fun ANBHome() {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    "Namaste! Main ANB AI hoon. Aaj main aapki kya help karun?",
                    false
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(Green, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "A",
                    color = Background,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ANB AI",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "PERSONAL ASSISTANT",
                    fontSize = 10.sp,
                    color = TextMuted,
                    letterSpacing = 1.5.sp
                )
            }

            Text(
                "● DEMO",
                color = Green,
                fontSize = 11.sp
            )
        }

        HorizontalDivider(color = Color(0xFF26332B))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, bottom = 16.dp)
        ) {
            Text(
                "Aapka personal AI.",
                color = TextPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Sochiye, poochhiye, aur kuch naya banaiye.",
                color = TextMuted,
                fontSize = 14.sp
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    }
                ) {
                    Text(
                        text = message.text,
                        color = if (message.isUser) Background else TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier
                            .widthIn(max = 310.dp)
                            .background(
                                if (message.isUser) Green else SurfaceDark,
                                RoundedCornerShape(18.dp)
                            )
                            .padding(
                                horizontal = 16.dp,
                                vertical = 12.dp
                            )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(SurfaceDark, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary,
                    fontSize = 15.sp
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                "ANB AI se poochhein...",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    val question = input.trim()
                    if (question.isNotEmpty()) {
                        messages = messages + ChatMessage(question, true) +
                            ChatMessage(
                                "Yeh ANB AI ka demo mode hai. Real AI responses agle development phase mein connect honge.",
                                false
                            )
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Text("↑", fontSize = 24.sp)
            }
        }

        Text(
            "ANB AI can make mistakes. Verify important information.",
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
        )
    }
}
