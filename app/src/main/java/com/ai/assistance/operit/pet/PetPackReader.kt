package com.ai.assistance.operit.pet

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import org.json.JSONObject

internal const val MAX_PET_BYTES = 16 * 1024 * 1024

internal class PetImportException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { TOO_LARGE, INVALID_PACK, INVALID_IMAGE, INVALID_VIDEO, FORMAT_MISMATCH }
}

internal fun copyPetBytes(input: InputStream, output: OutputStream, limit: Int = MAX_PET_BYTES) {
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        if (count == 0) continue
        total += count
        if (total > limit) throw PetImportException(PetImportException.Reason.TOO_LARGE)
        output.write(buffer, 0, count)
    }
}

/** Read only the manifest's image entry. Entry names are never used as filesystem paths. */
internal fun readPetPack(archive: File, target: OutputStream): String {
    try {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val manifest = entries.singleOrNull {
                !it.isDirectory && (it.name == "pet.json" || it.name.endsWith("/pet.json")) &&
                    !it.name.startsWith("__MACOSX/")
            } ?: throw PetImportException(PetImportException.Reason.INVALID_PACK)
            val bytes = java.io.ByteArrayOutputStream()
            zip.getInputStream(manifest).use { copyPetBytes(it, bytes, 64 * 1024) }
            val json = JSONObject(bytes.toString(Charsets.UTF_8.name()))
            if (json.optInt("spriteVersionNumber") != 2) {
                throw PetImportException(PetImportException.Reason.INVALID_PACK)
            }
            val imagePath = json.optString("spritesheetPath")
            val prefix = manifest.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val image = entries.singleOrNull { !it.isDirectory && it.name == prefix + imagePath }
                ?: throw PetImportException(PetImportException.Reason.INVALID_PACK)
            zip.getInputStream(image).use { copyPetBytes(it, target) }
            return json.optString("displayName").trim().take(80)
        }
    } catch (error: PetImportException) {
        throw error
    } catch (error: Exception) {
        throw PetImportException(PetImportException.Reason.INVALID_PACK)
    }
}
