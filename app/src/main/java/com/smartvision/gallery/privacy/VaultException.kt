package com.smartvision.gallery.privacy

sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HideFailed(cause: Throwable) : VaultException("Hide failed", cause)
    class UnhideFailed(cause: Throwable) : VaultException("Unhide failed", cause)
    class CorruptMetadata(val path: String, reason: String) :
        VaultException("Vault metadata corrupt at $path: $reason")
}