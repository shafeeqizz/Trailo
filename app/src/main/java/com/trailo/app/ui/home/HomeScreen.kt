package com.trailo.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trailo.app.R
import com.trailo.app.ui.theme.TrailoTheme

@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel()) {
    HomeScreen(state = viewModel.uiState, onToggleVision = viewModel::toggleVision)
}

@Composable
fun HomeScreen(state: HomeUiState, onToggleVision: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_description), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.foundation_status), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.feature_notice), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onToggleVision) {
                Text(stringResource(if (state.isVisionExpanded) R.string.hide_vision else R.string.show_vision))
            }
            if (state.isVisionExpanded) {
                Text(stringResource(R.string.product_vision), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    TrailoTheme {
        HomeScreen(state = HomeUiState(isVisionExpanded = true), onToggleVision = {})
    }
}
