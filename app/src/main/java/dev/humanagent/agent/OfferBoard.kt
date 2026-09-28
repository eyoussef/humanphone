package dev.humanagent.agent

/** One candidate the assistant compared: a flight, a room, a plan — whatever the task shopped for. */
data class Offer(
    val name: String,
    val price: Double?,
    val currency: String,
    val details: String,
    val recordedAtMs: Long,
)

/**
 * Per-run scratchpad for comparison shopping. The model records every candidate it sees with
 * `record_offer`, then `compare_offers` ranks them so the choice is the cheapest fitting one,
 * never the first search result. Lives only for one run: every task compares fresh.
 */
class OfferBoard {

    private val offers = LinkedHashMap<String, Offer>()

    /** Records or replaces the offer with this name. A null price means "could not read it". */
    fun record(name: String, price: Double?, currency: String, details: String): Offer {
        val cleanName = name.trim().take(120)
        require(cleanName.isNotEmpty()) { "name is required" }
        val offer = Offer(
            name = cleanName,
            price = price,
            currency = currency.trim().take(8),
            details = details.trim().take(400),
            recordedAtMs = System.currentTimeMillis(),
        )
        offers[cleanName.lowercase()] = offer
        return offer
    }

    /** The recorded candidates, cheapest first, prices we could not read last. */
    fun sortedByPrice(): List<Offer> =
        offers.values.sortedWith(compareBy({ it.price ?: Double.POSITIVE_INFINITY }, { it.name.lowercase() }))

    fun isEmpty(): Boolean = offers.isEmpty()

    fun size(): Int = offers.size

    /** The ranking the model reads, or a nudge when nothing was recorded yet. */
    fun compare(): String {
        if (offers.isEmpty()) {
            return "No offers recorded yet. Use record_offer for every option you find, then compare_offers."
        }
        val sorted = sortedByPrice()
        val lines = sorted.mapIndexed { index, offer ->
            val price = if (offer.price == null) "price not shown" else "${offer.price}${label(offer.currency)}"
            "${index + 1}. ${offer.name} — $price${if (offer.details.isBlank()) "" else " — ${offer.details}"}"
        }
        val best = sorted.firstOrNull { it.price != null }
        val bestLine = when {
            best == null -> "None of them shows a price yet; open the promising ones and record it."
            sorted.size < 3 -> "Best so far: ${best.name} at ${best.price}${label(best.currency)} — record more options before deciding."
            else -> "Best price: ${best.name} at ${best.price}${label(best.currency)}."
        }
        return buildString {
            appendLine("${sorted.size} offer${if (sorted.size == 1) "" else "s"}, cheapest first:")
            append(lines.joinToString("\n"))
            append('\n')
            append(bestLine)
        }.trimEnd()
    }

    private fun label(currency: String): String = if (currency.isBlank()) "" else " $currency"

    fun clear() = offers.clear()
}