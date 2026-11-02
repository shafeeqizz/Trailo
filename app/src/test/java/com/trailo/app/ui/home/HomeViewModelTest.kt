package com.trailo.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun `vision is collapsed on a new screen`() {
        assertFalse(HomeViewModel().uiState.isVisionExpanded)
    }

    @Test
    fun `vision can be expanded and collapsed`() {
        val viewModel = HomeViewModel()

        viewModel.toggleVision()
        assertTrue(viewModel.uiState.isVisionExpanded)

        viewModel.toggleVision()
        assertFalse(viewModel.uiState.isVisionExpanded)
    }
}
