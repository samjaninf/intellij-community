// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package fleet.multiplatform.shims

import fleet.util.multiplatform.Actual

@Actual("synchronizedImpl")
inline fun <T> synchronizedImplWasm(lock: Any, block: () -> Any?): Any? = block()