// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.core.script.k2

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import org.jetbrains.kotlin.idea.core.script.k2.configurations.DefaultScriptEntitySource
import org.jetbrains.kotlin.idea.core.script.k2.modules.ScriptCompilationConfigurationData
import org.jetbrains.kotlin.idea.core.script.k2.modules.ScriptCompilationConfigurationEntity
import org.jetbrains.kotlin.idea.core.script.k2.modules.ScriptCompilationConfigurationEntityId
import org.jetbrains.kotlin.idea.core.script.k2.modules.ScriptEvaluationConfigurationEntity
import org.jetbrains.kotlin.idea.core.script.k2.modules.ScriptingHostConfigurationEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.util.PropertiesCollection
import kotlin.script.experimental.util.PropertiesCollection.Key

fun ScriptCompilationConfiguration.asEntity(): ScriptCompilationConfigurationData = ScriptCompilationConfigurationData(this.asBytes())

fun ScriptCompilationConfigurationData.deserialize(): ScriptCompilationConfiguration? {
    val params = ByteArrayInputStream(data).use { bis ->
        ObjectInputStream(bis).use { ois ->
            ois.readObject() as? LinkedHashMap<Key<Any>, Any?>
        }
    } ?: return null

    return ScriptCompilationConfiguration {
        params.forEach { (key, any) ->
            key.putIfNotNull(any)
        }
    }
}

fun ByteArray.asCompilationConfiguration(): ScriptCompilationConfiguration? {
    val params = ByteArrayInputStream(this).use { bis ->
        ObjectInputStream(bis).use { ois ->
            ois.readObject() as? LinkedHashMap<Key<Any>, Any?>
        }
    } ?: return null

    return ScriptCompilationConfiguration {
        params.forEach { (key, any) ->
            key.putIfNotNull(any)
        }
    }
}

fun PropertiesCollection.asBytes(): ByteArray = ByteArrayOutputStream().use { bos ->
    ObjectOutputStream(bos).use { oos ->
        oos.writeObject(notTransientData)
    }

    bos.toByteArray()
}

fun ScriptEvaluationConfiguration.asEntity() = ScriptEvaluationConfigurationEntity(this.asBytes())

fun ScriptEvaluationConfigurationEntity.deserialize(): ScriptEvaluationConfiguration? {
    val params = ByteArrayInputStream(data).use { bis ->
        ObjectInputStream(bis).use { ois ->
            ois.readObject() as? LinkedHashMap<Key<Any>, Any?>
        }
    } ?: return null

    return ScriptEvaluationConfiguration {
        params.forEach { (key, any) ->
            key.putIfNotNull(any)
        }
    }
}

fun ScriptingHostConfiguration.asEntity() = ScriptingHostConfigurationEntity(this.asBytes())

fun ScriptingHostConfigurationEntity.deserialize(): ScriptingHostConfiguration? {
    val params = ByteArrayInputStream(data).use { bis ->
        ObjectInputStream(bis).use { ois ->
            ois.readObject() as? LinkedHashMap<Key<Any>, Any?>
        }
    } ?: return null

    return ScriptingHostConfiguration {
        params.forEach { (key, any) ->
            key.putIfNotNull(any)
        }
    }
}

fun MutableEntityStorage.getOrCreateScriptConfigurationEntityId(configuration: ScriptCompilationConfiguration, entitySource: EntitySource): ScriptCompilationConfigurationEntityId {
    val bytes = configuration.asBytes()
    val configurationEntityId = ScriptCompilationConfigurationEntityId(bytes)
    if (!this.contains(configurationEntityId)) {
        this addEntity ScriptCompilationConfigurationEntity(bytes, entitySource)
    }

    return configurationEntityId
}