package com.example.nearchat.data.state

data class ProfileState(
    val displayName: String = "",
    val email: String = "",
    val editedName: String = "",
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val showSignOutDialog: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false
)
