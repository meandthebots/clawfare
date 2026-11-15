package com.clawfare.importer

import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightEntry
import com.clawfare.model.FlightSegment
import com.clawfare.model.FlightValidator
import com.clawfare.model.TripType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Import result for a single flight entry.
 */
sealed class ImportResult {
    data class Success(
        val id: String,
        val flightEntry: FlightEntry,
    ) : ImportResult()

    data class Skipped(
        val index: Int,
        val reason: String,
        val shareLink: String? = null,
    ) : ImportResult()

    data class ValidationError(
        val index: Int,
        val errors: List<String>,
        val shareLink: String? = null,
    ) : ImportResult()
}

/**
 * Summary of an import operation.
 */
data class ImportSummary(
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val validationErrors: Int,
    val results: List<ImportResult>,
) {
    val successResults: List<ImportResult.Success>
        get() = results.filterIsInstance<ImportResult.Success>()

    val errorResults: List<ImportResult.ValidationError>
        get() = results.filterIsInstance<ImportResult.ValidationError>()

    val skippedResults: List<ImportResult.Skipped>
        get() = results.filterIsInstance<ImportResult.Skipped>()
}

/**
 * Import flights from JSON format as defined in schema.md.
 */
object JsonImporter {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Import flights from a JSON string containing an array of FlightEntry objects.
     *
     * @param jsonString JSON string with flight entries array
     * @param investigationSlug The investigation to associate flights with
     * @param skipDuplicates If true, skip entries with existing share_link (default: true)
     * @return ImportSummary with results for each entry
     */
    fun importFromJson(
        jsonString: String,
        investigationSlug: String,
        skipDuplicates: Boolean = true,
    ): ImportSummary {
        val entries =
            try {
                json.decodeFromString<List<FlightEntry>>(jsonString)
            } catch (e: Exception) {
                return ImportSummary(
                    total = 0,
                    imported = 0,
                    skipped = 0,
                    validationErrors = 1,
                    results =
                        listOf(
                            ImportResult.ValidationError(
                                index = 0,
                                errors = listOf("Failed to parse JSON: ${e.message}"),
                            ),
                        ),
                )
            }

        return importEntries(entries, investigationSlug, skipDuplicates)
    }

    /**
     * Import a list of FlightEntry objects.
     *
     * @param entries List of flight entries to import
     * @param investigationSlug The investigation to associate flights with
     * @param skipDuplicates If true, skip entries with existing share_link
     * @return ImportSummary with results for each entry
     */
    fun importEntries(
        entries: List<FlightEntry>,
        investigationSlug: String,
        skipDuplicates: Boolean = true,
    ): ImportSummary {
        val results = mutableListOf<ImportResult>()

        entries.forEachIndexed { index, entry ->
            val result = importSingleEntry(entry, investigationSlug, index, skipDuplicates)
            results.add(result)
        }

        val imported = results.count { it is ImportResult.Success }
        val skipped = results.count { it is ImportResult.Skipped }
        val validationErrors = results.count { it is ImportResult.ValidationError }

        return ImportSummary(
            total = entries.size,
            imported = imported,
            skipped = skipped,
            validationErrors = validationErrors,
            results = results,
        )
    }

    /**
     * Import a single flight entry.
     */
    private fun importSingleEntry(
        entry: FlightEntry,
        investigationSlug: String,
        index: Int,
        skipDuplicates: Boolean,
    ): ImportResult {
        // Check for duplicate share_link
        if (skipDuplicates) {
            val existing = FlightQueries.getByShareLink(entry.shareLink)
            if (existing != null) {
                return ImportResult.Skipped(
                    index = index,
                    reason = "Duplicate share_link (existing id: ${existing.id})",
                    shareLink = entry.shareLink,
                )
            }
        }

        // Validate the entry
        val validation = FlightValidator.validate(entry)
        if (!validation.isValid) {
            return ImportResult.ValidationError(
                index = index,
                errors = validation.errors,
                shareLink = entry.shareLink,
            )
        }

        // Convert to DTO and insert
        val dto = entryToDto(entry, investigationSlug)

        return try {
            FlightQueries.create(dto)

            // Also create initial price history entry
            PriceHistoryQueries.create(
                PriceHistoryDto(
                    flightId = dto.id,
                    amount = entry.priceAmount,
                    currency = entry.priceCurrency,
                    checkedAt = entry.priceCheckedAt,
                    priceMarket = entry.priceMarket,
                ),
            )

            ImportResult.Success(
                id = dto.id,
                flightEntry = entry,
            )
        } catch (e: Exception) {
            ImportResult.ValidationError(
                index = index,
                errors = listOf("Database insert failed: ${e.message}"),
                shareLink = entry.shareLink,
            )
        }
    }

    /**
     * Convert a FlightEntry to a FlightDto for database storage.
     * Note: Price fields are stored in PriceHistoryDto, not FlightDto.
     */
    fun entryToDto(
        entry: FlightEntry,
        investigationSlug: String,
    ): FlightDto {
        val outboundJson = json.encodeToString(FlightSegment.serializer(), entry.outbound)
        val returnJson = entry.returnSegment?.let { json.encodeToString(FlightSegment.serializer(), it) }
        val tagsJson = entry.tags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

        return FlightDto(
            id = entry.id,
            investigationSlug = investigationSlug,
            shareLink = entry.shareLink,
            source = entry.source,
            tripType =
                when (entry.tripType) {
                    TripType.ROUND_TRIP -> "round_trip"
                    TripType.ONE_WAY -> "one_way"
                },
            ticketStructure = entry.ticketStructure.name.lowercase(),
            priceAmount = entry.priceAmount,
            priceCurrency = entry.priceCurrency,
            priceMarket = entry.priceMarket,
            priceCheckedAt = entry.priceCheckedAt,
            origin = entry.origin,
            destination = entry.destination,
            outboundJson = outboundJson,
            returnJson = returnJson,
            bookingClass = entry.bookingClass,
            cabinMixed = entry.cabinMixed ?: false,
            notes = entry.notes,
            tags = tagsJson,
            capturedAt = entry.capturedAt,
        )
    }
}
