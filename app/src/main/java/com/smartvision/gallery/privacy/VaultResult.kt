package com.smartvision.gallery.privacy

sealed class VaultResult {
    data class Hidden(val vaultId: String) : VaultResult()
    data object Unhidden : VaultResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : VaultResult()
}