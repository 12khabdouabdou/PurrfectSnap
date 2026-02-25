package me.eternal.purrfectsnap.mapper.impl

import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import android.util.Log
import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.findConstString
import me.eternal.purrfectsnap.mapper.ext.getClassName
import me.eternal.purrfectsnap.mapper.ext.searchNextFieldReference

class ActiveStatusMapper : AbstractClassMapper("ActiveSort") {
    val orchestratorClass = classReference("orchestrator_class")
    val responseHandler = classReference("response_handler")
    val thresholdField = string("threshold_field")
    val dataStoreClass = classReference("data_store_class")
    val cacheUpdateMethod = string("cache_update_method")

    val sendToProviderClass = classReference("send_to_provider_class")
    val sendToProviderMethod = string("send_to_provider_method")
    val bulkMessagingProviderClass = classReference("bulk_messaging_provider_class")
    val bulkMessagingProviderMethod = string("bulk_messaging_provider_method")

    init {
        mapper {
            for (classDef in classes) {
                // Orchestrator Structural Match:
                // 1. apply(Object) method
                // 2. Contains a Map field
                // 3. Calls System.currentTimeMillis()
                // 4. References TimeUnit.MINUTES
                
                val applyMethod = classDef.methods.firstOrNull { it.name == "apply" && it.parameterTypes.size == 1 } ?: continue
                val hasMapField = classDef.fields.any { it.type == "Ljava/util/Map;" }
                if (!hasMapField) continue
                
                val impl = applyMethod.implementation ?: continue
                if (!impl.findConstString("MINUTES", contains = true)) continue
                
                var callsCurrentTimeMillis = false
                var referencesTimeUnit = false
                
                for (instruction in impl.instructions) {
                    if (instruction is ReferenceInstruction) {
                        val ref = instruction.reference
                        if (ref is MethodReference) {
                            if (ref.name == "currentTimeMillis" && ref.definingClass == "Ljava/lang/System;") {
                                callsCurrentTimeMillis = true
                            }
                            if (ref.definingClass == "Ljava/util/concurrent/TimeUnit;") {
                                referencesTimeUnit = true
                            }
                        }
                    }
                }
                
                if (!callsCurrentTimeMillis || !referencesTimeUnit) continue
                
                // Found Orchestrator
                orchestratorClass.set(classDef.getClassName())
                
                // Trace Threshold Field: static int field immediately before TimeUnit.MINUTES.toMillis()
                runCatching {
                    thresholdField.set(impl.searchNextFieldReference("MINUTES", contains = true)?.name)
                }.onFailure {
                    Log.w("ActiveStatusMapper", "could not resolve threshold_field")
                }

                // Trace Response Handler: trace the lambda in the Orchestrator's return statement
                // Usually the orchestrator returns a Single/Observable/Flowable mapped by a handler.
                // We look for the creation of a handler instance (new-instance opcode) in the apply method.
                runCatching {
                    for (instruction in impl.instructions) {
                        if (instruction is ReferenceInstruction && instruction.opcode.name.contains("new-instance")) {
                            val ref = instruction.reference
                            if (ref is com.android.tools.smali.dexlib2.iface.reference.TypeReference) {
                                val type = ref.type
                                if (type.startsWith("L") && !type.startsWith("Ljava/")) {
                                    // Potential candidate for response_handler (obfuscated lambda/handler)
                                    responseHandler.set(type.replaceFirst("L", "").replaceFirst(";", ""))
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w("ActiveStatusMapper", "could not resolve response_handler")
                }

                // Trace Data Store Class: trace DI chain from Orchestrator via gRPC Client (C36842q0f)
                runCatching {
                    // C36842q0f is likely a class name or string mentioned in the DI chain.
                    // We search for a class that depends on the orchestrator or mentions the client.
                    for (candidate in classes) {
                        for (method in candidate.methods) {
                            val candidateImpl = method.implementation ?: continue
                            // Search for DI signatures or the recentlyActiveFriendStoringFactory concept
                            if (candidateImpl.findConstString("recentlyActiveFriendStoringFactory", contains = true)) {
                                dataStoreClass.set(candidate.getClassName())
                                // Find cache update method: search for method taking Map<String, Boolean> or returning it
                                val potentialMethod = candidate.methods.firstOrNull { 
                                    (it.parameterTypes.size == 1 && it.parameterTypes[0] == "Ljava/util/Map;") ||
                                    (it.returnType == "Ljava/util/Map;" && it.parameterTypes.isEmpty())
                                }
                                cacheUpdateMethod.set(potentialMethod?.name)
                                break
                            }
                        }
                        if (dataStoreClass.get() != null) break
                    }
                    
                    // Fallback using the hint C36842q0f if string matching fails
                    if (dataStoreClass.get() == null) {
                        val grpcClientClass = classes.firstOrNull { it.type.contains("q0f") }
                        if (grpcClientClass != null) {
                            // Find who references this gRPC client in a way that suggests storage
                            // This is a placeholder for more advanced tracing if needed.
                        }
                    }
                }.onFailure {
                    Log.w("ActiveStatusMapper", "could not resolve data_store_class")
                }

                // Trace Phase 2 Targets: SendTo and Bulk Messaging Providers
                runCatching {
                    for (candidate in classes) {
                        for (method in candidate.methods) {
                            val candidateImpl = method.implementation ?: continue
                            
                            // SendTo Provider Match
                            if (sendToProviderClass.get() == null && candidateImpl.findConstString("SendTo", contains = true)) {
                                val listMethod = candidate.methods.firstOrNull { it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() }
                                if (listMethod != null) {
                                    sendToProviderClass.set(candidate.getClassName())
                                    sendToProviderMethod.set(listMethod.name)
                                }
                            }

                            // Bulk Messaging Provider Match
                            if (bulkMessagingProviderClass.get() == null && candidateImpl.findConstString("BulkMessaging", contains = true)) {
                                val listMethod = candidate.methods.firstOrNull { it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() }
                                if (listMethod != null) {
                                    bulkMessagingProviderClass.set(candidate.getClassName())
                                    bulkMessagingProviderMethod.set(listMethod.name)
                                }
                            }
                        }
                        if (sendToProviderClass.get() != null && bulkMessagingProviderClass.get() != null) break
                    }
                }

                // Log outcome
                val targets = listOf(orchestratorClass, responseHandler, thresholdField, dataStoreClass, cacheUpdateMethod, sendToProviderClass, sendToProviderMethod, bulkMessagingProviderClass, bulkMessagingProviderMethod)
                val count = targets.count { it.get() != null }
                if (count == targets.size) {
                    Log.v("ActiveStatusMapper", "complete — $count/${targets.size} targets resolved")
                } else {
                    Log.w("ActiveStatusMapper", "partially complete — $count/${targets.size} targets resolved")
                }
                
                return@mapper
            }
        }
    }
}
