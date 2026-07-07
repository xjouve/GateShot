package com.gateshot.ui.athlete

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

@Composable
fun AthleteScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var showAddForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var bibNumbers by remember { mutableStateOf("") }
    var ageGroup by remember { mutableStateOf("U16") }
    var team by remember { mutableStateOf("") }
    var athletes by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    // Load athletes on first render
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.getAthletes { athletes = it }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color(0xFF444444),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color.Gray
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Athletes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Surface(
                onClick = { showAddForm = !showAddForm },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.Add, null, tint = Color.Black, modifier = Modifier.height(16.dp))
                    Text("Add", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Add athlete form
        if (showAddForm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B2A))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("NEW ATHLETE", color = Color(0xFF8899AA), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors, singleLine = true
                )
                OutlinedTextField(
                    value = bibNumbers, onValueChange = { bibNumbers = it },
                    label = { Text("Bib numbers (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors, singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Age group chips
                    listOf("U14", "U16", "U18", "Senior").forEach { group ->
                        Surface(
                            onClick = { ageGroup = group },
                            shape = RoundedCornerShape(8.dp),
                            color = if (ageGroup == group) MaterialTheme.colorScheme.primary else Color(0xFF333333)
                        ) {
                            Text(
                                group,
                                color = if (ageGroup == group) Color.Black else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = team, onValueChange = { team = it },
                    label = { Text("Team / Club") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors, singleLine = true
                )
                Surface(
                    onClick = {
                        if (name.isNotBlank()) {
                            viewModel.onCreateAthlete(name, bibNumbers, ageGroup, team) {
                                viewModel.getAthletes { athletes = it }
                            }
                            name = ""; bibNumbers = ""; team = ""
                            showAddForm = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Save Athlete",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Athlete list
        if (athletes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Person, null, tint = Color(0xFF444444),
                        modifier = Modifier.height(48.dp).width(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No athletes yet", color = Color.Gray, fontSize = 14.sp)
                    Text("Add athletes to track their progress", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        } else {
            athletes.forEach { athlete ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1A2A3A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Avatar
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(40.dp).width(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    (athlete["name"] ?: "?").take(2).uppercase(),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                athlete["name"] ?: "Unknown",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bibs = athlete["bibNumbers"]
                                if (!bibs.isNullOrBlank()) {
                                    Text("Bibs: $bibs", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                                }
                                val group = athlete["ageGroup"]
                                if (!group.isNullOrBlank()) {
                                    Text(group, color = Color(0xFF8899AA), fontSize = 12.sp)
                                }
                                val teamName = athlete["team"]
                                if (!teamName.isNullOrBlank()) {
                                    Text(teamName, color = Color(0xFF667788), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
