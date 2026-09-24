package com.tsfdroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.tsfdroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AutoReplyViewModel @Inject constructor(
    val settingsRepository: SettingsRepository
) : ViewModel()
