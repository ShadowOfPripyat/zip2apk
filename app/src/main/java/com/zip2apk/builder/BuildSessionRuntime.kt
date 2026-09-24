package com.zip2apk.builder

import com.zip2apk.builder.model.BuilderUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide state for the one active source preparation/build session. */
object BuildSessionRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = MutableStateFlow(BuilderUiState())

    @Volatile var preparationJob: Job? = null
    @Volatile var buildJob: Job? = null
    @Volatile var cancelActive: (() -> Unit)? = null
}
