package com.horis.anikoto

/**
 * JavaScript unpacker for Dean Edwards' packer.
 * Handles eval(function(p,a,c,k,e,d){...}) style packed JS.
 */
object JsUnpacker {

    fun unpack(packed: String): String? {
        try {
            // Extract the parameters from the packed function
            val pattern = Regex(
                """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = pattern.find(packed) ?: return null

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: return null
            val count = match.groupValues[3].toIntOrNull() ?: return null
            val keywords = match.groupValues[4].split("|")

            if (keywords.size != count) {
                // Try with a different count - some packers don't match exactly
            }

            var result = payload
            for (i in (count - 1) downTo 0) {
                if (i < keywords.size && keywords[i].isNotEmpty()) {
                    val word = baseConvert(i, radix)
                    result = result.replace(Regex("\\b$word\\b"), keywords[i])
                }
            }

            return result.replace("\\\\", "\\")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
        } catch (e: Exception) {
            return null
        }
    }

    private fun baseConvert(num: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        if (base > chars.length) return num.toString()

        val sb = StringBuilder()
        var n = num
        while (n > 0) {
            sb.insert(0, chars[n % base])
            n /= base
        }
        return sb.toString()
    }

    /**
     * Check if a string contains packed JavaScript
     */
    fun isPacked(text: String): Boolean {
        return text.contains("eval(function(p,a,c,k,e,")
    }
}
