package opensec.pibolt.app.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's time zone as a TZif file (RFC 8536) in `PREFIX/share/zoneinfo`, where the pack's glibc and Python look
 * for `TZ` (the pack ships no tz database, and Android's own is in a format glibc cannot read). Without it, `TZ=Asia/Kolkata`
 * resolves to UTC: `date`, Python's local time and git's commit times were in UTC, not the phone's time.
 *
 * The file is made from Android's rules (java.time): every transition up to [UNTIL], and a POSIX TZ footer for the
 * years after when the rules can be written as one.
 */
object ZoneInfo {
    /** Explicit transitions up to here (the footer, when there is one, covers later years). */
    private val UNTIL: Instant = Instant.parse("2038-01-19T03:14:07Z")
    private val FROM: Instant = Instant.parse("1800-01-01T00:00:00Z")

    /** Writes [id]'s file under [dir] unless it is there already with the same contents. False when [id] is unusable. */
    fun ensure(dir: File, id: String): Boolean {
        if (!safeId(id)) return false
        val bytes = runCatching { tzif(ZoneId.of(id)) }.getOrNull() ?: return false
        val file = File(dir, id)
        if (file.isFile && runCatching { file.readBytes().contentEquals(bytes) }.getOrDefault(false)) return true
        return runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.delete()
                error("rename")
            }
        }.isSuccess
    }

    /** A zone id usable as a path under the zoneinfo directory ("Asia/Kolkata", "Etc/GMT+5", "GMT+05:30"). */
    internal fun safeId(id: String): Boolean {
        val parts = id.split('/')
        return id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it in "_+-:/" } &&
            parts.none { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }
    }

    private data class Type(val offset: Int, val dst: Boolean, val name: String)

    /** [zone] as TZif version 2: its transitions from [FROM] to [UNTIL] and a POSIX TZ footer (empty when none fits). */
    fun tzif(zone: ZoneId): ByteArray {
        val rules = zone.rules
        val names = Names(zone)
        val times = ArrayList<Long>()
        val typeOf = ArrayList<Type>()
        var t = rules.nextTransition(FROM)
        val first = t?.offsetBefore ?: rules.getOffset(Instant.now())
        val initial = Type(first.totalSeconds, rules.isDaylightSavings(t?.instant?.minusSeconds(1) ?: Instant.now()), "")
        while (t != null && t.instant.isBefore(UNTIL)) {
            val after = t.offsetAfter.totalSeconds
            times += t.instant.epochSecond
            typeOf += Type(after, rules.isDaylightSavings(t.instant), "")
            t = rules.nextTransition(t.instant)
        }
        // Designations: the zone's own short names where they apply, else the numeric form ("+0530").
        fun named(type: Type) = type.copy(name = names.of(type.offset, type.dst, rules, Instant.now()))
        val types = ArrayList<Type>()
        fun index(type: Type): Int = types.indexOf(type).takeIf { it >= 0 } ?: types.size.also { types += type }
        index(named(initial))
        val indices = typeOf.map { index(named(it)) }
        val chars = ByteArrayOutputStream()
        val nameAt = HashMap<String, Int>()
        for (type in types) nameAt.getOrPut(type.name) { chars.size().also { chars.write(type.name.toByteArray(Charsets.US_ASCII)); chars.write(0) } }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        // Version 1 block: only the first type (readers of version 2 skip it).
        header(data, timecnt = 0, typecnt = 1, charcnt = types[0].name.length + 1)
        data.writeInt(types[0].offset)
        data.writeByte(if (types[0].dst) 1 else 0)
        data.writeByte(0)
        data.write(types[0].name.toByteArray(Charsets.US_ASCII))
        data.writeByte(0)
        // Version 2 block: 64-bit times.
        header(data, timecnt = times.size, typecnt = types.size, charcnt = chars.size())
        for (time in times) data.writeLong(time)
        for (i in indices) data.writeByte(i)
        for (type in types) {
            data.writeInt(type.offset)
            data.writeByte(if (type.dst) 1 else 0)
            data.writeByte(nameAt.getValue(type.name))
        }
        data.write(chars.toByteArray())
        data.write('\n'.code)
        data.write((posix(rules, names) ?: "").toByteArray(Charsets.US_ASCII))
        data.write('\n'.code)
        data.flush()
        return out.toByteArray()
    }

    private fun header(data: DataOutputStream, timecnt: Int, typecnt: Int, charcnt: Int) {
        data.write("TZif2".toByteArray(Charsets.US_ASCII))
        data.write(ByteArray(15))
        data.writeInt(0) // isutcnt
        data.writeInt(0) // isstdcnt
        data.writeInt(0) // leapcnt
        data.writeInt(timecnt)
        data.writeInt(typecnt)
        data.writeInt(charcnt)
    }

    /** The zone's short names for its current standard and daylight offsets (Android's, in English). */
    private class Names(zone: ZoneId) {
        private val tz = TimeZone.getTimeZone(zone)
        private val standard = tz.getDisplayName(false, TimeZone.SHORT, Locale.US)
        private val daylight = tz.getDisplayName(true, TimeZone.SHORT, Locale.US)

        fun of(offset: Int, dst: Boolean, rules: ZoneRules, now: Instant): String {
            val std = rules.getStandardOffset(now).totalSeconds
            val name = when {
                !dst && offset == std -> standard
                dst && offset == std + tz.dstSavings / 1000 -> daylight
                else -> null
            }
            return name?.takeIf { n -> n.length in 3..6 && n.all { it in 'A'..'Z' } } ?: numeric(offset)
        }
    }

    /** "+0530", "-03", "+00": the designation tzdata uses for zones without letters. */
    internal fun numeric(offset: Int): String {
        val sign = if (offset < 0) '-' else '+'
        val abs = kotlin.math.abs(offset)
        val h = abs / 3600
        val m = abs % 3600 / 60
        return "%c%02d".format(Locale.ROOT, sign, h) + (if (m != 0) "%02d".format(Locale.ROOT, m) else "")
    }

    /** A designation in a POSIX TZ string: letters as they are, anything else in angle brackets. */
    private fun posixName(name: String) = if (name.all { it.isLetter() }) name else "<$name>"

    /** A POSIX offset: the time to add to local time for UTC ("-5:30" for UTC+5:30). */
    private fun posixOffset(utcOffset: Int): String = posixTime(-utcOffset)

    private fun posixTime(seconds: Int): String {
        val sign = if (seconds < 0) "-" else ""
        val abs = kotlin.math.abs(seconds)
        val h = abs / 3600
        val m = abs % 3600 / 60
        val s = abs % 60
        return sign + h + (if (m != 0 || s != 0) ":%02d".format(Locale.ROOT, m) else "") + (if (s != 0) ":%02d".format(Locale.ROOT, s) else "")
    }

    /** [zone]'s rules after its last transition as a POSIX TZ string ("IST-5:30"), or null when they have no such form. */
    internal fun posix(zone: ZoneId): String? = posix(zone.rules, Names(zone))

    private fun posix(rules: ZoneRules, n: Names): String? {
        val last = rules.transitionRules
        if (last.isEmpty()) {
            // No rules for later years: the offset after the last transition stays.
            val offset = rules.getOffset(UNTIL).totalSeconds
            val name = n.of(offset, false, rules, UNTIL)
            return posixName(name) + posixOffset(offset)
        }
        if (last.size != 2) return null
        val std = last[0].standardOffset.totalSeconds
        val dst = last.firstOrNull { it.offsetAfter.totalSeconds != std } ?: return null
        val end = last.firstOrNull { it !== dst } ?: return null
        if (end.offsetAfter.totalSeconds != std || end.standardOffset.totalSeconds != std) return null
        val dstOffset = dst.offsetAfter.totalSeconds
        val stdName = n.of(std, false, rules, UNTIL)
        val dstName = n.of(dstOffset, true, rules, UNTIL)
        val start = rule(dst) ?: return null
        val stop = rule(end) ?: return null
        return buildString {
            append(posixName(stdName)).append(posixOffset(std))
            append(posixName(dstName))
            if (dstOffset != std + 3600) append(posixOffset(dstOffset))
            append(',').append(start).append(',').append(stop)
        }
    }

    /** "M3.2.0/2": a transition rule in POSIX form, or null when it has no such form (a fixed day of the month). */
    private fun rule(r: ZoneOffsetTransitionRule): String? {
        val dow = r.dayOfWeek ?: return null
        val d = r.dayOfMonthIndicator
        val week = when {
            d == -1 -> 5
            // java.time writes "lastSun" of a 31- or 30-day month as "Sun>=25" or "Sun>=24".
            d > 0 && d + 6 == r.month.maxLength() && r.month != Month.FEBRUARY -> 5
            d > 0 && (d - 1) % 7 == 0 && d <= 22 -> (d - 1) / 7 + 1
            else -> return null
        }
        // POSIX times are local times in the offset before the transition.
        var seconds = r.localTime.toSecondOfDay() + (if (r.isMidnightEndOfDay) 86_400 else 0)
        val before = r.offsetBefore.totalSeconds
        seconds += when (r.timeDefinition) {
            ZoneOffsetTransitionRule.TimeDefinition.UTC -> before
            ZoneOffsetTransitionRule.TimeDefinition.STANDARD -> before - r.standardOffset.totalSeconds
            else -> 0
        }
        val day = if (dow == DayOfWeek.SUNDAY) 0 else dow.value
        return "M${r.month.value}.$week.$day" + (if (seconds != 7200) "/" + posixTime(seconds) else "")
    }
}
