package com.fileflow.app.ui

import androidx.lifecycle.ViewModel
import com.fileflow.app.data.repo.ActiveOperation
import com.fileflow.app.data.repo.OperationRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place that knows an operation is running, whatever started it.
 *
 * Progress used to be drawn by whichever screen began the work, so leaving
 * that screen also lost the progress bar and the Stop button — while the copy
 * kept running with no way to see or end it. The runner is a singleton and its
 * state is a StateFlow, so this ViewModel simply re-exposes it at the root and
 * every screen renders the same indicator.
 */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val operationRunner: OperationRunner,
) : ViewModel() {

    val activeOperation: StateFlow<ActiveOperation?> get() = operationRunner.active

    fun cancelOperation() = operationRunner.cancel()
}
