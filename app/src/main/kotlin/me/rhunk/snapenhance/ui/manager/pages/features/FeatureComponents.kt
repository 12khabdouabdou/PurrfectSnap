package me.rhunk.snapenhance.ui.manager.pages.features

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.skydoves.colorpicker.compose.AlphaSlider
import com.github.skydoves.colorpicker.compose.AlphaTile
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import me.rhunk.snapenhance.common.bridge.wrapper.LocaleWrapper
import me.rhunk.snapenhance.common.config.*
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableStateList
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.CircularAlphaTile
import me.rhunk.snapenhance.ui.util.chooseFolder

typealias ClickCallback = (Boolean) -> Unit

@Composable
fun DefaultDialogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .padding(16.dp)
            .then(modifier),
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp, 10.dp, 10.dp, 10.dp)
                .verticalScroll(ScrollState(0)),
        ) { content() }
    }
}

@Composable
fun TranslatedText(translation: LocaleWrapper, property: PropertyPair<*>, key: String, modifier: Modifier = Modifier) {
    Text(
        text = property.key.propertyOption(translation, key),
        modifier = Modifier
            .padding(10.dp, 10.dp, 10.dp, 10.dp)
            .then(modifier)
    )
}

@Composable
@Suppress("UNCHECKED_CAST")
fun UniqueSelectionDialog(translation: LocaleWrapper, property: PropertyPair<*>) {
    val keys = (property.value.defaultValues as List<String>).toMutableList().apply {
        add(0, "null")
    }

    var selectedValue by remember {
        mutableStateOf(property.value.getNullable()?.toString() ?: "null")
    }

    DefaultDialogCard {
        keys.forEachIndexed { index, item ->
            fun select() {
                selectedValue = item
                property.value.setAny(if (index == 0) {
                    null
                } else {
                    item
                })
            }

            Row(
                modifier = Modifier.clickable { select() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                TranslatedText(
                    translation = translation,
                    property = property,
                    key = item,
                    modifier = Modifier.weight(1f)
                )
                RadioButton(
                    selected = selectedValue == item,
                    onClick = { select() }
                )
            }
        }
    }
}

@Composable
fun KeyboardInputDialog(translation: LocaleWrapper, property: PropertyPair<*>, dismiss: () -> Unit = {}) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    DefaultDialogCard {
        var fieldValue by remember {
            mutableStateOf(property.value.get().toString().let {
                TextFieldValue(
                    text = it,
                    selection = TextRange(it.length)
                )
            })
        }

        TextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 10.dp)
                .onGloballyPositioned {
                    focusRequester.requestFocus()
                }
                .focusRequester(focusRequester),
            value = fieldValue,
            onValueChange = { fieldValue = it },
            keyboardOptions = when (property.key.dataType.type) {
                DataProcessors.Type.INTEGER -> KeyboardOptions(keyboardType = KeyboardType.Number)
                DataProcessors.Type.FLOAT -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
                else -> KeyboardOptions(keyboardType = KeyboardType.Text)
            },
            singleLine = true
        )

        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = { dismiss() }) {
                Text(text = translation["button.cancel"])
            }
            Button(onClick = {
                if (fieldValue.text.isNotEmpty() && property.key.params.inputCheck?.invoke(fieldValue.text) == false) {
                    Toast.makeText(context, "Invalid input! Make sure you entered a valid value.", Toast.LENGTH_SHORT).show() //TODO: i18n
                    return@Button
                }

                when (property.key.dataType.type) {
                    DataProcessors.Type.INTEGER -> {
                        runCatching {
                            property.value.setAny(fieldValue.text.toInt())
                        }.onFailure {
                            property.value.setAny(0)
                        }
                    }
                    DataProcessors.Type.FLOAT -> {
                        runCatching {
                            property.value.setAny(fieldValue.text.toFloat())
                        }.onFailure {
                            property.value.setAny(0f)
                        }
                    }
                    else -> property.value.setAny(fieldValue.text)
                }
                dismiss()
            }) {
                Text(text = translation["button.ok"])
            }
        }
    }
}

@Composable
@Suppress("UNCHECKED_CAST")
fun MultipleSelectionDialog(translation: LocaleWrapper, property: PropertyPair<*>) {
    val defaultItems = property.value.defaultValues as List<String>
    val toggledStates = property.value.get() as MutableList<String>
    DefaultDialogCard {
        defaultItems.forEach { key ->
            var state by remember { mutableStateOf(toggledStates.contains(key)) }

            fun toggle(value: Boolean? = null) {
                state = value ?: !state
                if (state) {
                    toggledStates.add(key)
                } else {
                    toggledStates.remove(key)
                }
            }

            Row(
                modifier = Modifier.clickable { toggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                TranslatedText(
                    translation = translation,
                    property = property,
                    key = key,
                    modifier = Modifier
                        .weight(1f)
                )
                Switch(
                    checked = state,
                    onCheckedChange = {
                        toggle(it)
                    }
                )
            }
        }
    }
}

@Composable
fun ColorPickerPropertyDialog(
    property: PropertyPair<*>,
    dismiss: () -> Unit = {},
) {
    var currentColor by remember {
        mutableStateOf((property.value.getNullable() as? Int)?.let { Color(it) })
    }

    DefaultDialogCard {
        val controller = remember { ColorPickerController().apply {
            if (currentColor == null) {
                setWheelAlpha(1f)
                setBrightness(1f, false)
            }
        } }
        var colorHexValue by remember {
            mutableStateOf(currentColor?.toArgb()?.let { Integer.toHexString(it) } ?: "")
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            TextField(
                value = colorHexValue,
                onValueChange = { value ->
                    colorHexValue = value
                    runCatching {
                        currentColor = Color(android.graphics.Color.parseColor("#$value")).also {
                            controller.selectByColor(it, true)
                            property.value.setAny(it.toArgb())
                        }
                    }.onFailure {
                        currentColor = null
                    }
                },
                label = { Text(text = "Hex Color") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                )
            )
        }
        HsvColorPicker(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(10.dp),
            initialColor = remember { currentColor },
            controller = controller,
            onColorChanged = {
                if (!it.fromUser) return@HsvColorPicker
                currentColor = it.color
                colorHexValue = Integer.toHexString(it.color.toArgb())
                property.value.setAny(it.color.toArgb())
            }
        )
        AlphaSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .height(35.dp),
            initialColor = remember { currentColor },
            controller = controller,
        )
        BrightnessSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .height(35.dp),
            initialColor = remember { currentColor },
            controller = controller,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlphaTile(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(6.dp)),
                controller = controller
            )
            IconButton(onClick = {
                property.value.setAny(property.value.defaultValues?.firstOrNull() ?: return@IconButton)
                dismiss()
            }) {
                Icon(
                    modifier = Modifier.size(60.dp),
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
fun PropertyDialog(
    property: PropertyPair<*>,
    routes: Routes,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(routes.context.translation[property.key.propertyName()]) },
        text = {
            when (property.key.dataType.type) {
                DataProcessors.Type.STRING_UNIQUE_SELECTION -> UniqueSelectionDialog(routes.context.translation, property)
                DataProcessors.Type.STRING_MULTIPLE_SELECTION -> MultipleSelectionDialog(routes.context.translation, property)
                DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> KeyboardInputDialog(routes.context.translation, property, onDismiss)
                DataProcessors.Type.INT_COLOR -> ColorPickerPropertyDialog(property, onDismiss)
                DataProcessors.Type.MAP_COORDINATES -> routes.alertDialogs.ChooseLocationDialog(property, onDismiss)
                else -> {}
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


@Composable
fun PropertyAction(
    property: PropertyPair<*>,
    routes: Routes
) {
    val propertyValue = property.value

    when (val dataType = remember { property.key.dataType.type }) {
        DataProcessors.Type.BOOLEAN -> {
            var state by remember { mutableStateOf(propertyValue.get() as Boolean) }
            val hapticFeedback = LocalHapticFeedback.current
            Switch(
                checked = state,
                onCheckedChange = {
                    if (routes.context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    state = !state
                    propertyValue.setAny(state)
                }
            )
        }
        DataProcessors.Type.STRING_UNIQUE_SELECTION -> {
            Text(
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.widthIn(0.dp, 120.dp),
                text = (propertyValue.getNullable() as? String ?: "null").let {
                    property.key.propertyOption(routes.context.translation, it)
                }
            )
        }
        DataProcessors.Type.STRING_MULTIPLE_SELECTION, DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> {
            if (dataType == DataProcessors.Type.INTEGER ||
                dataType == DataProcessors.Type.FLOAT) {
                Text(
                    text = propertyValue.get().toString(),
                    modifier = Modifier.wrapContentWidth(),
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            }
        }
        DataProcessors.Type.INT_COLOR -> {
            CircularAlphaTile(selectedColor = (propertyValue.getNullable() as? Int)?.let { Color(it) })
        }
        DataProcessors.Type.CONTAINER -> {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        }
        DataProcessors.Type.MAP_COORDINATES -> {
             Text(
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.widthIn(0.dp, 120.dp),
                text = (propertyValue.get() as Pair<*, *>).let {
                    "${it.first.toString().toFloatOrNull() ?: 0F}, ${it.second.toString().toFloatOrNull() ?: 0F}"
                }
            )
        }
        else -> {}
    }
}

@Composable
fun PropertyCard(
    property: PropertyPair<*>,
    routes: Routes,
    isInEditMode: Boolean,
    onEdit: (() -> Unit)? = null
) {
    val showDialog = remember { mutableStateOf(false) }

    if (showDialog.value) {
        PropertyDialog(property = property, routes = routes, onDismiss = { showDialog.value = false })
    }

    val noticeColorMap = mapOf(
        FeatureNotice.UNSTABLE.key to Color(0xFFFFFB87),
        FeatureNotice.BAN_RISK.key to Color(0xFFFF8585),
        FeatureNotice.INTERNAL_BEHAVIOR.key to Color(0xFFFFFB87),
    )

    val versionCheck = remember { property.key.params.versionCheck }
    val versionCheckPair = remember(property) { versionCheck?.checkVersion(routes.context.installationSummary.snapchatInfo?.versionCode ?: return@remember null)}
    val isComponentDisabled = remember { versionCheckPair != null && versionCheck?.isDisabled == true }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
            .then(
                if (isComponentDisabled) Modifier.graphicsLayer(alpha = 0.5f)
                else Modifier
            )
            .clickable(enabled = isInEditMode || onEdit != null) {
                if (onEdit != null) {
                    onEdit()
                } else if (isInEditMode) {
                    showDialog.value = true
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            property.key.params.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 10.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = true)
                    .padding(all = 10.dp)
            ) {
                Text(
                    text = routes.context.translation[property.key.propertyName()],
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = routes.context.translation[property.key.propertyDescription()],
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
                property.key.params.notices.also {
                    if (it.isNotEmpty()) Spacer(modifier = Modifier.height(5.dp))
                }.forEach {
                    Text(
                        text = routes.context.translation["features.notices.${it.key}"],
                        color = noticeColorMap[it.key] ?: Color(0xFFFFFB87),
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                }

                if (versionCheckPair != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = routes.context.translation.format(
                            "manager.sections.features.${versionCheckPair.second.key}",
                            "version" to versionCheckPair.first.first
                        ),
                        color = Color(0xFFFF8585),
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            if (isInEditMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(all = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PropertyAction(property, routes)
                }
            } else if (onEdit != null) {
                IconButton(onClick = {
                    if (onEdit != null) {
                        onEdit()
                    }
                }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
            }
        }
    }
}

@Composable
fun PropertiesView(
    properties: List<PropertyPair<*>>,
    routes: Routes,
    isInEditMode: Boolean,
    onEdit: ((PropertyPair<*>) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        items(properties, key = { it.key.propertyName() }) {
            PropertyCard(it, routes, isInEditMode, onEdit = { onEdit?.invoke(it) })
        }
    }
}
