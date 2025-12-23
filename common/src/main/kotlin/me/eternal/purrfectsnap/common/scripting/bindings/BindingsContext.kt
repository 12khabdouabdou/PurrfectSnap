package me.eternal.purrfectsnap.common.scripting.bindings

import me.eternal.purrfectsnap.common.scripting.JSModule
import me.eternal.purrfectsnap.common.scripting.ScriptRuntime
import me.eternal.purrfectsnap.common.scripting.type.ModuleInfo

class BindingsContext(
    val moduleInfo: ModuleInfo,
    val runtime: ScriptRuntime,
    val module: JSModule
)