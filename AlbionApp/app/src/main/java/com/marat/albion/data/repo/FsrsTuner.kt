package com.marat.albion.data.repo

import com.marat.albion.data.fsrs.OptimizeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TuningState {
    data object Idle : TuningState
    data class Running(val progress: Float) : TuningState
    data class Finished(val outcome: OptimizeOutcome) : TuningState
    data class Failed(val message: String) : TuningState
}

/**
 * Запуск подстройки FSRS в фоне приложения: подбор продолжается,
 * даже если пользователь ушёл с экрана настроек.
 */
class FsrsTuner(private val study: StudyRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<TuningState>(TuningState.Idle)
    val state: StateFlow<TuningState> = _state.asStateFlow()

    fun run() {
        if (_state.value is TuningState.Running) return
        _state.value = TuningState.Running(0f)
        scope.launch {
            _state.value = try {
                TuningState.Finished(study.optimizeParams { p -> _state.value = TuningState.Running(p) })
            } catch (e: CancellationException) {
                _state.value = TuningState.Idle
                throw e
            } catch (t: Throwable) {
                TuningState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        scope.launch { study.resetParams() }
        _state.value = TuningState.Idle
    }
}
