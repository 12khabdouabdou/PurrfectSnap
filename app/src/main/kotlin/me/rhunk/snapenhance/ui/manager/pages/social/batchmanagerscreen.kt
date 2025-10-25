package me.rhunk.snapenhance.ui.manager.pages.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.core.features.impl.messaging.BatchFriendSelector
import me.rhunk.snapenhance.common.ui.createComposeActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchManagerScreen(
    sessionId: String,
    batchFeature: BatchFriendSelector,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<BatchFriendSelector.BatchSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(sessionId) {
        session = batchFeature.getBatchSession(sessionId)
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🐱 Batch Friend Selector", fontSize = 18.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Fermer")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Session introuvable", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Statistics Card
                BatchStatisticsCard(session!!)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Batch List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(session!!.batches) { batch ->
                        BatchCard(
                            batch = batch,
                            batchNumber = session!!.batches.indexOf(batch) + 1,
                            onSend = {
                                scope.launch {
                                    batchFeature.sendBatch(sessionId, batch.id)
                                    // Refresh session
                                    session = batchFeature.getBatchSession(sessionId)
                                }
                            },
                            onCancel = {
                                scope.launch {
                                    batchFeature.cancelBatch(sessionId, batch.id)
                                    session = batchFeature.getBatchSession(sessionId)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                
                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                session!!.batches
                                    .filter { it.status == BatchFriendSelector.BatchStatus.PENDING }
                                    .forEach { batch ->
                                        batchFeature.sendBatch(sessionId, batch.id)
                                        kotlinx.coroutines.delay(2000) // Delay between batches
                                    }
                                session = batchFeature.getBatchSession(sessionId)
                            }
                        },
                        enabled = session!!.batches.any { 
                            it.status == BatchFriendSelector.BatchStatus.PENDING 
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tout envoyer")
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                batchFeature.deleteSession(sessionId)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Annuler")
                    }
                }
            }
        }
    }
}

@Composable
fun BatchStatisticsCard(session: BatchFriendSelector.BatchSession) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("📊 Total amis", "${session.friendIds.size}")
                StatItem("📦 Batches", "${session.batches.size}")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val sentCount = session.batches.count { it.status == BatchFriendSelector.BatchStatus.SENT }
                val pendingCount = session.batches.count { it.status == BatchFriendSelector.BatchStatus.PENDING }
                
                StatItem("✓ Envoyés", "$sentCount/${session.batches.size}")
                StatItem("⏸️ En attente", "$pendingCount")
            }
            
            // Progress bar
            Spacer(modifier = Modifier.height(12.dp))
            val progress = session.batches.count { 
                it.status == BatchFriendSelector.BatchStatus.SENT 
            }.toFloat() / session.batches.size.toFloat()
            
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun BatchCard(
    batch: BatchFriendSelector.FriendBatch,
    batchNumber: Int,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val (statusColor, statusIcon, statusText) = when (batch.status) {
        BatchFriendSelector.BatchStatus.PENDING -> Triple(
            Color(0xFFFFA726),
            Icons.Default.Refresh,
            "En attente"
        )
        BatchFriendSelector.BatchStatus.SENDING -> Triple(
            Color(0xFF42A5F5),
            Icons.Default.Send,
            "En cours"
        )
        BatchFriendSelector.BatchStatus.SENT -> Triple(
            Color(0xFF66BB6A),
            Icons.Default.CheckCircle,
            "Envoyé"
        )
        BatchFriendSelector.BatchStatus.FAILED -> Triple(
            Color(0xFFEF5350),
            Icons.Default.Error,
            "Échec"
        )
        BatchFriendSelector.BatchStatus.CANCELLED -> Triple(
            Color(0xFF9E9E9E),
            Icons.Default.Cancel,
            "Annulé"
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch #$batchNumber",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        statusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Friend count
            Text(
                text = "${batch.friendIds.size} amis",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            // Error message if failed
            if (batch.status == BatchFriendSelector.BatchStatus.FAILED && batch.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Erreur: ${batch.error}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            // Sent timestamp
            if (batch.sentAt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Envoyé: ${formatTimestamp(batch.sentAt)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            // Action buttons
            if (batch.status == BatchFriendSelector.BatchStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSend,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Envoyer")
                    }
                    
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Annuler")
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return when {
        seconds < 60 -> "Il y a ${seconds}s"
        minutes < 60 -> "Il y a ${minutes}m"
        hours < 24 -> "Il y a ${hours}h"
        else -> {
            val date = java.util.Date(timestamp)
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH).format(date)
        }
    }
}