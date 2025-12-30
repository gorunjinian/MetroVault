package com.gorunjinian.metrovault.domain.service.multisig

import android.util.Log

object BSMS {

    private const val TAG = "BSMS"

    // BIP-0129 Constants
    const val VERSION = "BSMS 1.0"
    const val NO_PATH_RESTRICTIONS = "No path restrictions"
    const val LINE_SEPARATOR = "\n"  // LF per BIP-0129 spec

    // Standard path restrictions for receive/change
    const val STANDARD_PATH_RESTRICTIONS = "/0/*,/1/*"

    /**
     * BSMS Descriptor Record data class.
     * Represents the 4-line unencrypted descriptor record format.
     */
    data class DescriptorRecord(
        val version: String,
        val descriptor: String,
        val pathRestrictions: String,
        val firstAddress: String
    ) {
        /**
         * Format as BSMS string (4 lines, LF separated).
         */
        fun format(): String = buildString {
            append(version)
            append(LINE_SEPARATOR)
            append(descriptor)
            append(LINE_SEPARATOR)
            append(pathRestrictions)
            append(LINE_SEPARATOR)
            append(firstAddress)
        }
    }

    /**
     * Result of parsing a BSMS format string.
     * Uses Result<DescriptorRecord, String> for success with DescriptorRecord and failure with String error message.
     */

    /**
     * Result of BSMS data extraction (intermediate step before full parsing).
     * Used when we just need the descriptor and optional metadata.
     */
    data class ExtractedData(
        val descriptor: String,
        val pathRestrictions: String? = null,
        val verificationAddress: String? = null,
        val isBsmsFormat: Boolean = false
    )

    // ==================== Parsing Functions ====================

    /**
     * Extract BSMS data from input string.
     * This is a more lenient version that extracts what it can find.
     * Used during import when we need the descriptor but validation happens later.
     *
     * @param input The input string (BSMS format or plain descriptor)
     * @return ExtractedData with whatever was found
     */
    fun extractFromInput(input: String): ExtractedData {
        val trimmed = input.trim()
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Check for BSMS format
        if (lines.isNotEmpty() && lines[0].uppercase().startsWith("BSMS")) {
            Log.d(TAG, "Detected BSMS format")

            var descriptor: String? = null
            var pathRestrictions: String? = null
            var verificationAddress: String? = null

            for (i in 1 until lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("#") -> continue
                    isDescriptorLine(line) -> descriptor = line
                    line.startsWith("/") -> pathRestrictions = line
                    line.equals(NO_PATH_RESTRICTIONS, ignoreCase = true) -> {
                        pathRestrictions = NO_PATH_RESTRICTIONS
                    }
                    isBitcoinAddress(line) -> verificationAddress = line
                }
            }

            if (descriptor != null) {
                return ExtractedData(
                    descriptor = descriptor,
                    pathRestrictions = pathRestrictions,
                    verificationAddress = verificationAddress,
                    isBsmsFormat = true
                )
            }
            Log.w(TAG, "BSMS format detected but no descriptor found, falling back to raw")
        }

        // Handle plain descriptor format (with optional comments)
        for (line in lines) {
            if (line.startsWith("#")) continue
            if (isDescriptorLine(line)) {
                return ExtractedData(
                    descriptor = line,
                    isBsmsFormat = false
                )
            }
        }

        // Fallback: return input as-is
        return ExtractedData(
            descriptor = trimmed,
            isBsmsFormat = false
        )
    }

    // ==================== Formatting Functions ====================

    /**
     * Create a BSMS Descriptor Record from a descriptor and first address.
     * Automatically extracts path restrictions from the descriptor template.
     *
     * @param descriptor The output descriptor (with or without checksum)
     * @param firstAddress The first receive address for verification
     * @return DescriptorRecord ready for export
     */
    fun createDescriptorRecord(descriptor: String, firstAddress: String): DescriptorRecord {
        // Remove checksum if present
        val cleanDescriptor = removeChecksum(descriptor)

        // Extract path restrictions from descriptor template
        val pathRestrictions = extractPathRestrictions(cleanDescriptor)

        return DescriptorRecord(
            version = VERSION,
            descriptor = cleanDescriptor,
            pathRestrictions = pathRestrictions,
            firstAddress = firstAddress
        )
    }

    /**
     * Format a descriptor as BSMS string.
     * Convenience function that creates and formats a DescriptorRecord.
     *
     * @param descriptor The output descriptor
     * @param firstAddress The first receive address
     * @return BSMS formatted string
     */
    fun formatDescriptor(descriptor: String, firstAddress: String): String {
        return createDescriptorRecord(descriptor, firstAddress).format()
    }

    fun extractPathRestrictions(descriptor: String): String {
        // Look for multipath wildcard pattern: /<0;1>/* or /<0;1;2>/*
        val multipathPattern = Regex("""/<(\d+(?:;\d+)*)>/\*""")
        val multipathMatch = multipathPattern.find(descriptor)

        if (multipathMatch != null) {
            val indices = multipathMatch.groupValues[1].split(";")
            val paths = indices.map { "/$it/*" }
            return paths.joinToString(",")
        }

        // Look for simple wildcard: /* or /<num>/*
        val simpleWildcardPattern = Regex("""/(\d+)?\*""")
        if (simpleWildcardPattern.containsMatchIn(descriptor)) {
            // Descriptor has wildcards, assume standard paths
            return STANDARD_PATH_RESTRICTIONS
        }

        // No wildcards found - might be a concrete descriptor
        // Check if descriptor contains any derivation paths at all
        if (descriptor.contains("/") && !descriptor.contains("*")) {
            // Has paths but no wildcards - this is a single-address descriptor
            return NO_PATH_RESTRICTIONS
        }

        // Default: assume standard paths for templates
        return STANDARD_PATH_RESTRICTIONS
    }

    /**
     * Check if a string looks like a descriptor line.
     */
    private fun isDescriptorLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("wsh(") ||
               lower.startsWith("sh(") ||
               lower.startsWith("wpkh(") ||
               lower.startsWith("pkh(") ||
               lower.startsWith("tr(")
    }

    /**
     * Check if a string looks like a Bitcoin address.
     */
    private fun isBitcoinAddress(line: String): Boolean {
        // Match common Bitcoin address patterns
        return line.matches(Regex("^(tb1|bc1|[123mn])[a-zA-Z0-9]{25,90}$"))
    }

    /**
     * Remove checksum from descriptor if present.
     * Checksums appear after # at the end: wsh(...)#checksum
     */
    fun removeChecksum(descriptor: String): String {
        return descriptor.substringBeforeLast("#").trim()
    }
}
