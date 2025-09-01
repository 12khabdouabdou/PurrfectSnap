package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.rhunk.snapenhance.manager.ui.tab.Tab
import kotlin.random.Random

class SettingsTab : Tab("settings", isPrimary = true, icon = Icons.Default.Settings) {

    @Composable
    override fun Content() {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF181A24), Color(0xFF232241)))
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(25.dp))
                Text(
                    "Settings",
                    fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFFF4F3EC),
                    letterSpacing = 0.4.sp
                )
                Text(
                    "Tune SnapEnhance to your needs!",
                    fontSize = 14.sp,
                    color = Color(0xFFB8BAE4),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))

                BeautifulConfigEditRow(
                    getValue = { sharedConfig.snapEnhancePackageName },
                    setValue = { sharedConfig.snapEnhancePackageName = it },
                    label = "Override SnapEnhance package name",
                    icon = Icons.Default.Edit,
                    randomValueProvider = {
                        (0..Random.nextInt(7, 16)).map { ('a'..'z').random() }.joinToString("").chunked(4).joinToString(".")
                    }
                )

                BeautifulConfigBooleanRow(
                    getValue = { sharedConfig.enableRepackage },
                    setValue = { sharedConfig.enableRepackage = it },
                    label = "Repackage SnapEnhance (experimental)"
                )

                BeautifulConfigBooleanRow(
                    getValue = { sharedConfig.useRootInstaller },
                    setValue = { sharedConfig.useRootInstaller = it },
                    label = "Use root installer"
                )

                BeautifulConfigBooleanRow(
                    getValue = { sharedConfig.obfuscateLSPatch },
                    setValue = { sharedConfig.obfuscateLSPatch = it },
                    label = "Obfuscate LSPatch (experimental)"
                )
            }

            SnapEnhanceFloatingNav(this@SettingsTab)
        }
    }
}

@Composable
private fun BeautifulConfigEditRow(
    getValue: () -> String?, setValue: (String) -> Unit, label: String,
    icon: ImageVector,
    randomValueProvider: (() -> String)? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        val focusRequester = remember { FocusRequester() }
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF232241)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    var textFieldValue by remember { mutableStateOf((getValue() ?: "").let {
                        TextFieldValue(it, TextRange(it.length))
                    }) }
                    TextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onGloballyPositioned { focusRequester.requestFocus() },
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = Color(0xFF191A2D),
                            focusedIndicatorColor = Color(0xFF8B9AE0),
                            unfocusedIndicatorColor = Color(0xFF444563)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        OutlinedButton(onClick = { showDialog = false }) {
                            Text(text = "Cancel")
                        }
                        if (randomValueProvider != null) {
                            OutlinedButton(onClick = {
                                textFieldValue = TextFieldValue(randomValueProvider(), TextRange(0))
                            }) {
                                Text(text = "Random")
                            }
                        }
                        Button(onClick = {
                            setValue(textFieldValue.text)
                            showDialog = false
                        }) {
                            Text(text = "Save")
                        }
                    }
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(7.dp)
            .clickable { showDialog = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = Color(0xFFFDF7C3)
            )
            Text(
                text = getValue() ?: "(Not specified)",
                fontSize = 12.sp,
                color = Color(0xFF98A2CF)
            )
        }
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun BeautifulConfigBooleanRow(
    getValue: () -> Boolean, setValue: (Boolean) -> Unit, label: String
) {
    var value by remember { mutableStateOf(getValue()) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(7.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { value = !value; setValue(value) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Text(
                text = label, fontSize = 15.sp,
                color = Color(0xFFFDF7C3),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = value,
                onCheckedChange = {
                    value = it
                    setValue(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFF5DC5C),
                    uncheckedThumbColor = Color.LightGray
                ),
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}
