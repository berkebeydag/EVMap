package com.berke.ioniqscope.charging

/**
 * What each network charges per kWh, as it published it.
 *
 * Per network, not per station, because that is how charging is priced in Türkiye:
 * EPDK's 2026 change to the Şarj Hizmeti Yönetmeliği removed tiering by power, so an
 * operator sets one AC price and one DC price and every socket it owns uses them. That
 * turns "what does this cost" from a fact about 16,104 stations into a fact about
 * twenty networks, which is small enough to carry.
 *
 * Carried rather than fetched, deliberately. A price endpoint would be one more thing
 * to be rate limited by, to fail on a road with no signal, and to need a key; and the
 * numbers move a few times a year, not a few times a day. Updating them is a release,
 * which is the honest cost of not having a server.
 *
 * The ranges are real. Several networks still quote two figures — membership against
 * walk-up, or a location premium — and ZES says outright on its own pricing page that
 * "fiyatlar lokasyon bazlı değişebilir". So this is a guide to what a stop will cost,
 * never a quote, and the UI has to say so rather than presenting it as the price.
 *
 * Sources: the operators' own pricing pages where they serve them as plain HTML
 * (zes.net, aksasarj.com.tr), and the comparison tables at araclo.com and
 * arabamsarj.com.tr for the rest. Keys are spelled exactly as the bundle spells its
 * operators — [tools/build_bundle_from_sweep.py] folds them, so anything not matching
 * one of those names simply has no tariff and shows nothing.
 */
object ChargerTariffs {

    /** Turkish lira per kWh. [from] and [to] are equal where a network quotes one price. */
    data class Band(val from: Double, val to: Double) {
        val varies: Boolean get() = to > from
    }

    data class Tariff(val ac: Band?, val dc: Band?)

    /** When these figures were last checked against their sources. */
    const val AS_OF = "25.07.2026"

    private fun one(price: Double) = Band(price, price)
    private fun range(from: Double, to: Double) = Band(from, to)

    private val table = mapOf(
        "ZES" to Tariff(one(9.99), range(12.99, 16.49)),
        "Trugo" to Tariff(one(9.95), one(14.98)),
        "Voltrun" to Tariff(one(9.90), one(12.90)),
        "WAT Mobilite" to Tariff(range(9.99, 10.99), range(10.99, 14.49)),
        "Eşarj" to Tariff(one(9.90), range(9.90, 14.90)),
        "Otopriz" to Tariff(one(9.90), one(13.90)),
        "Otojet" to Tariff(one(10.99), one(15.49)),
        "En Yakıt" to Tariff(null, one(14.90)),
        "Otowatt" to Tariff(one(10.99), one(13.99)),
        "Sharz" to Tariff(one(9.49), one(11.99)),
        "Aksa Şarj" to Tariff(range(9.90, 10.90), range(12.49, 13.49)),
        "Beefull" to Tariff(one(9.90), one(13.99)),
        "ovolt" to Tariff(one(9.99), one(13.99)),
        "EPSIS" to Tariff(one(9.90), one(10.50)),
        "D-Charge" to Tariff(one(9.40), one(12.99)),
        "5 Şarj" to Tariff(one(8.99), one(15.90)),
        "Oncharge" to Tariff(one(9.99), one(13.50)),
        "Astor Şarj" to Tariff(one(9.89), one(13.99)),
        "Efish" to Tariff(one(10.49), one(13.49)),
        "CV Charging" to Tariff(one(9.99), one(13.49)),
        "PowerŞarj" to Tariff(one(11.19), one(13.99)),
        "Shell Recharge" to Tariff(one(11.99), range(13.50, 15.99)),
        "Toger" to Tariff(one(9.39), one(13.39)),
        "ToraŞarj" to Tariff(one(9.99), one(13.49))
    )

    fun forOperator(operator: String?): Tariff? = operator?.let(table::get)

    /**
     * The band that applies to a site, given what it is.
     *
     * Falls back to DC where the source never said which it is: a driver reading a
     * price wants to know the worst it might be, and DC is both the dearer and the one
     * they are usually looking for.
     */
    fun bandFor(operator: String?, isDc: Boolean?): Band? {
        val tariff = forOperator(operator) ?: return null
        return if (isDc == false) tariff.ac else tariff.dc ?: tariff.ac
    }
}
