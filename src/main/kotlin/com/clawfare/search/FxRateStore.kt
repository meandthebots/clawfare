package com.clawfare.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

data class FxSnapshot(
    val date: String,
    val perGbp: Map<String, Double>,
)

/**
 * A dated GBP-base reference snapshot from Frankfurter. Refresh is explicit:
 * comparing saved prices or running tests never contacts the FX API.
 */
object FxRateStore {
    private const val ENDPOINT = "https://api.frankfurter.dev/v1/latest?base=GBP"

    fun defaultFile(): File =
        File(
            System.getenv("CLAWFARE_FX_FILE")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.home")}/.clawfare/fx-rates.json",
        )

    fun parse(raw: String): FxSnapshot {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root["base"]?.jsonPrimitive?.content?.uppercase() == "GBP") { "FX snapshot must use GBP as base" }
        val date = root["date"]?.jsonPrimitive?.content ?: error("Missing FX date")
        LocalDate.parse(date)
        val rates =
            root["rates"]
                ?.jsonObject
                ?.mapNotNull { (currency, value) ->
                val rate = value.jsonPrimitive.doubleOrNull
                if (rate != null && rate > 0.0 && rate.isFinite()) currency.uppercase() to rate else null
                }?.toMap() ?: error("Missing FX rates")
        require("USD" in rates && "EUR" in rates) { "FX snapshot requires USD and EUR" }
        return FxSnapshot(date, rates + ("GBP" to 1.0))
    }

    fun load(path: File = defaultFile()): FxSnapshot? = if (!path.isFile) null else runCatching { parse(path.readText()) }.getOrNull()

    fun refresh(
        http: HttpFetcher = JdkHttpFetcher(),
        path: File = defaultFile(),
    ): FxSnapshot {
        val result = http.request("GET", ENDPOINT, emptyMap(), null)
        require(result.status in 200..299) { "FX reference service HTTP ${result.status}" }
        val snapshot = parse(result.body)
        path.parentFile?.mkdirs()
        val temporary = File.createTempFile("fx-rates-", ".json", path.parentFile)
        try {
            temporary.writeText(result.body)
            try {
                Files.move(
                    temporary.toPath(),
                    path.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        Fx.useSnapshot(snapshot)
        return snapshot
    }
}
