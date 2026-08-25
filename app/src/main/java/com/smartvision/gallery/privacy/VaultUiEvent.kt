package com.smartvision.gallery.privacy

/**
 * UI-facing event surface for the privacy vault.
 *
 * EncryptedPrivacyVault emits one of these per callback so callers can render
 * snackbars/toasts without re-implementing biometric plumbing.
 */
sealed class VaultUiEvent {
    data object AuthSucceeded : VaultUiEvent()
    data object AuthCancelled : VaultUiEvent()
    data class AuthFailed(val message: String) : VaultUiEvent()
    data class AuthError(val code: Int, val message: String) : VaultUiEvent()
    data class HideSucceeded(val vaultId: String) : VaultUiEvent()
    data class UnhideSucceeded(val vaultId: String) : VaultUiEvent()
    data class HideFailed(val message: String) : VaultUiEvent()
}