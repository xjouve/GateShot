package com.gateshot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.R

/** Recording entry point. The phone's media library is not shown here. */
@Composable
fun HomeScreen(onStartSession: () -> Unit, onRecord: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.home_title), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Record and review ski runs in GateShot", color = Color.LightGray, fontSize = 16.sp)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Videocam, contentDescription = null)
            Text("Record with GateShot", modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Uses the phone's periscope camera. Tap the racer to set the autofocus area.",
            color = Color.LightGray, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onStartSession) { Text(stringResource(R.string.home_new_session)) }
    }
}
