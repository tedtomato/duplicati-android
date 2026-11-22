package io.github.tedtomato.duplicatirestore.format

import com.google.gson.JsonParser

data class Manifest(val blocksize: Int, val blockHash: String, val fileHash: String) {
    companion object {
        fun parse(bytes: ByteArray): Manifest {
            var text = String(bytes, Charsets.UTF_8)
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            val o = JsonParser.parseString(text).asJsonObject
            val m = Manifest(
                blocksize = o.get("Blocksize").asInt,
                blockHash = o.get("BlockHash")?.asString ?: "SHA256",
                fileHash = o.get("FileHash")?.asString ?: "SHA256",
            )
            if (!m.blockHash.equals("SHA256", true) || !m.fileHash.equals("SHA256", true)) {
                throw IllegalStateException("Only SHA256 hashes are supported (backup uses ${m.blockHash}/${m.fileHash})")
            }
            return m
        }
    }
}
