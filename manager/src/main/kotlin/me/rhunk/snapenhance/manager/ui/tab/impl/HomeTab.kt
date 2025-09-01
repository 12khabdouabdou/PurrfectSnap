package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import me.rhunk.snapenhance.manager.R
import me.rhunk.snapenhance.manager.ui.tab.Tab

class HomeTab : Tab("home", true, icon = Icons.Default.Home) {
    override fun init(activity: ComponentActivity) {
        super.init(activity)
        registerNestedTab(ManualPatchTab::class)
        registerNestedTab(AutoPatchTab::class)
        registerNestedTab(SettingsTab::class)
    }

    @Composable
    override fun Content() {
        // -- State for root/non-root toggle --
        var isRoot by remember { mutableStateOf(false) }
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp, start = 18.dp, end = 18.dp, bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo with rounded corners, shadow
            Surface(
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 12.dp,
                tonalElevation = 1.dp,
                modifier = Modifier.size(100.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.logo), // Your logo.png
                    contentDescription = "Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text("Welcome to Snapenhance Manager!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "This lets you setup Snapenhance easily",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Made with ❤️ by ΞTΞRNAL & rhunk",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(22.dp))

            // Telegram / Donate / GitHub Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/snapenhance_chat"))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_telegram),
                        contentDescription = "Telegram",
                        tint = Color(0xFF229ED9),
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance?tab=readme-ov-file#donate"))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "Donate",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rhunk/SnapEnhance"))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = "GitHub",
                        tint = Color(0xFF191A1A),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(34.dp))

            // Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Non-Root", fontWeight = if (!isRoot) FontWeight.Bold else null)
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = isRoot,
                    onCheckedChange = { isRoot = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(10.dp))
                Text("Root", fontWeight = if (isRoot) FontWeight.Bold else null)
            }

            Spacer(modifier = Modifier.height(30.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Manual Patch
                HomeButton(
                    label = "Manual Patch",
                    icon = Icons.Default.Handyman,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { navigation.navigateTo(ManualPatchTab::class) }
                )
                // Auto Patch
                HomeButton(
                    label = "Auto Patch",
                    icon = Icons.Default.AutoFixHigh,
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = { navigation.navigateTo(AutoPatchTab::class) }
                )
            }
        }

        // Floating iOS-like bottom nav
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp, start = 18.dp, end = 18.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                modifier = Modifier
                    .height(62.dp)
                    .fillMaxWidth(0.88f)
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = { navigation.navigateTo(HomeTab::class) }
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = "Home", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { navigation.navigateTo(SettingsTab::class) }
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = color,
        shadowElevation = 12.dp,
        modifier = Modifier
            .width(150.dp)
            .height(120.dp)
            .clickable { onClick() }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
        }
    }
}
