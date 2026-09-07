package com.gateshot.ui.athlete

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.R
import com.gateshot.ui.MainViewModel

@Composable
fun AthleteScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    // In-progress "Add" form survives navigation via the VM (this screen is
    // disposed on sub-tab / tab switches)
    val session = viewModel.coachSession
    var showAddForm by remember { mutableStateOf(session.athleteFormOpen) }
    var name by remember { mutableStateOf(session.athleteName) }
    var bibNumbers by remember { mutableStateOf(session.athleteBibs) }
    var ageGroup by remember { mutableStateOf(session.athleteAgeGroup) }
    var team by remember { mutableStateOf(session.athleteTeam) }
    var athletes by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var athleteToDelete by remember { mutableStateOf<Map<String, String>?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            session.athleteFormOpen = showAddForm
            session.athleteName = name
            session.athleteBibs = bibNumbers
            session.athleteAgeGroup = ageGroup
            session.athleteTeam = team
        }
    }

    // Load athletes on first render
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.getAthletes {
            // getAthletes swallows failures into an empty list and reports
            // the error via the global snackbar (MainViewModel.uiMessages),
            // so a failed load and a genuinely empty roster both land here.
            athletes = it
            isLoading = false
        }
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
            Text(
                stringResource(R.string.athlete_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                onClick = { showAddForm = !showAddForm },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.athlete_add_button), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                Text(stringResource(R.string.athlete_form_header), color = Color(0xFF8899AA), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.athlete_label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors, singleLine = true
                )
                OutlinedTextField(
                    value = bibNumbers, onValueChange = { bibNumbers = it },
                    label = { Text(stringResource(R.string.athlete_label_bibs)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors, singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Age group chips
                    val ageGroups = listOf(
                        stringResource(R.string.athlete_age_u14),
                        stringResource(R.string.athlete_age_u16),
                        stringResource(R.string.athlete_age_u18),
                        stringResource(R.string.athlete_age_senior)
                    )
                    ageGroups.forEach { group ->
                        Surface(
                            onClick = { ageGroup = group },
                            shape = RoundedCornerShape(8.dp),
                            color = if (ageGroup == group) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    group,
                                    color = if (ageGroup == group) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = team, onValueChange = { team = it },
                    label = { Text(stringResource(R.string.athlete_label_team)) },
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
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.athlete_save),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Athlete list
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.athlete_loading), color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else if (athletes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Person, null, tint = Color(0xFF444444),
                        modifier = Modifier.height(48.dp).width(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.athlete_empty_title), color = Color.Gray, fontSize = 14.sp)
                    Text(stringResource(R.string.athlete_empty_body), color = Color(0xFF666666), fontSize = 12.sp)
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
                                athlete["name"] ?: stringResource(R.string.athlete_unknown_name),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bibs = athlete["bibNumbers"]
                                if (!bibs.isNullOrBlank()) {
                                    Text(stringResource(R.string.athlete_bibs_label, bibs), color = Color(0xFF4FC3F7), fontSize = 12.sp)
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
                        IconButton(
                            onClick = { athleteToDelete = athlete },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.athlete_cd_delete),
                                tint = Color(0xFFEF5350)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    val pendingDelete = athleteToDelete
    if (pendingDelete != null) {
        val deleteName = pendingDelete["name"] ?: stringResource(R.string.athlete_unknown_name)
        AlertDialog(
            onDismissRequest = { athleteToDelete = null },
            title = { Text(stringResource(R.string.athlete_confirm_delete_title)) },
            text = { Text(stringResource(R.string.athlete_confirm_delete_text, deleteName)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = pendingDelete["id"]?.toLongOrNull()
                    athleteToDelete = null
                    if (id != null) {
                        viewModel.onDeleteAthlete(id) {
                            viewModel.getAthletes { athletes = it }
                        }
                    }
                }) { Text(stringResource(R.string.athlete_delete), color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { athleteToDelete = null }) { Text(stringResource(R.string.athlete_cancel)) }
            }
        )
    }
}
