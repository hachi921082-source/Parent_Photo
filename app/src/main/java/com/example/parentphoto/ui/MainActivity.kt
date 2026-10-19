package com.example.parentphoto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.parentphoto.ui.theme.FamilyPhotoTheme

/** 唯一のActivity。今は空のCompose画面を表示するだけ(画面は後続の機能で追加)。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyPhotoTheme {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}
