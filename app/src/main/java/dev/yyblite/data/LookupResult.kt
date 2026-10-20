package dev.yyblite.data

sealed interface LookupResult {
    data class Found(val app: StoreApp) : LookupResult
    data object NotInStore : LookupResult
    data class Failed(val reason: String) : LookupResult
}
