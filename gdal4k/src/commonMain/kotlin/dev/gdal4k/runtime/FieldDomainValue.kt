package dev.gdal4k.runtime

/** An enumeration code and optional display label. Codes remain text, including Int64. */
data class CodedFieldValue(val code: String, val label: String? = null)
/** Exact numeric text, or an ISO 8601 date/time for DateTime domains. Null bounds are unbounded. */
data class FieldDomainBound(val value: String, val inclusive: Boolean = true)
