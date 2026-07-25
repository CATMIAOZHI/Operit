package com.ai.assistance.operit.data.db

internal fun legacyFolderName(value: String?): String? = value?.takeUnless(String::isBlank)

internal fun nextAvailableExactFolderName(
    requestedName: String,
    usedNames: MutableSet<String>,
): String {
    val baseName = requestedName.takeUnless(String::isBlank) ?: "Folder"
    var candidate = baseName
    var suffix = 2
    while (!usedNames.add(candidate)) {
        candidate = "$baseName ($suffix)"
        suffix++
    }
    return candidate
}
