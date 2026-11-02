package com.trailo.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class HomeUiState(val isVisionExpanded: Boolean = false)

class HomeViewModel : ViewModel() {
    var uiState by mutableStateOf(HomeUiState())
        private set

    fun toggleVision() {
        uiState = uiState.copy(isVisionExpanded = !uiState.isVisionExpanded)
    }
}
