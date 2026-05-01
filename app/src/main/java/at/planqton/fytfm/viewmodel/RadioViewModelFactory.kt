package at.planqton.fytfm.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.data.PresetRepository

class RadioViewModelFactory(
    private val radioController: RadioController,
    private val presetRepository: PresetRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RadioViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return RadioViewModel(radioController, presetRepository) as T
    }
}
