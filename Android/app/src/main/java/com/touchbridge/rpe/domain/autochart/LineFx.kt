package com.touchbridge.rpe.domain.autochart

import kotlinx.serialization.json.*
import kotlin.math.*

object LineFx {
    data class Result(val events: JsonArray?, val lines: List<JsonObject>, val notes: List<JsonObject>, val extended: Map<String, JsonArray> = emptyMap())

    fun apply(notes: List<JsonObject>, analysis: AutoAnalysis, options: AutoChartOptions): Result =
        Renderer(notes, analysis, options).render()

    private data class Event(val from: Double, val to: Double, val start: Double, val end: Double, val ease: Int = 1, val bezier: Boolean = false)
    private data class Window(val from: Double, val to: Double) {
        operator fun contains(beat: Double) = beat >= from && beat < to
    }
    private data class Hit(val beat: Double, val type: Int, val x: Double)
    private data class Effect(val type: String, val whenTag: String)
    private data class Pose(val x: Double, val y: Double, val angle: Double)

    private class Renderer(val notes: List<JsonObject>, val analysis: AutoAnalysis, val options: AutoChartOptions) {
        private val tpb = analysis.ticksPerBeat
        private val beatsPerSec = analysis.bpm / 60.0
        private val sections = analysis.sections
        private val end = 999999.0
        private val last = floor((sections.maxOfOrNull { it.endTime } ?: 120.0) * beatsPerSec)
        private val hits = notes.filter { it["isFake"]?.jsonPrimitive?.intOrNull != 1 }.map {
            Hit(beat(it.getValue("startTime")), it.getValue("type").jsonPrimitive.int, it.getValue("positionX").jsonPrimitive.double)
        }.sortedBy { it.beat }
        private val taps = hits.filter { it.type == AutoChartConfig.TAP || it.type == AutoChartConfig.FLICK }

        fun render(): Result {
            val mode = options.lineFx
            val effects = if (mode == "mix") parseMix() else listOf(Effect(mode, when (mode) {
                "pinwheel", "curve" -> options.decoWhen
                "grid" -> "intro_chorus"
                else -> "climax"
            }))
            val types = effects.map { it.type }
            val ext = mutableMapOf<String, JsonArray>()
            if (types.any { it == "color_flow" || it == "glow" }) ext["colorEvents"] = colors()
            if ("breathe" in types) {
                val scale = events(listOf(constant(1.0)) + sections.flatMap {
                    val w = window(it)
                    val mid = (w.from + w.to) / 2
                    val amp = 1 + .06 * it.density.coerceIn(0.0, 1.0)
                    listOf(Event(w.from, mid, 1.0, amp, 6), Event(mid, w.to, amp, 1.0, 6))
                })
                ext["scaleXEvents"] = scale
                ext["scaleYEvents"] = scale
            }
            val (rot, x) = if (options.mainSwing || mode == "main_swing" || "echo_trail" in types) swing() else emptyList<Event>() to emptyList()
            val (lift, finalNotes) = if (mode == "mix") lift() else emptyList<Event>() to notes
            val y = if ("bounce" in types) {
                val bounceTimes = taps.map { it.beat }.filter { abs(it - round(it)) < 1e-6 }.distinct()
                addBounce(lift, bounceTimes)
            } else lift
            val pulse = mode != "none" && mode != "main_swing"
            val lead = AutoChartConfig.LEAD_IN_BEATS.toDouble()
            val alpha = listOf(Event(0.0, lead, 0.0, 255.0)) + if (pulse) sections.flatMap {
                val b = window(it).to
                listOf(Event(b, b + .25, 255.0, 190.0), Event(b + .25, b + .75, 190.0, 255.0))
            } else listOf(Event(lead, end, 255.0, 255.0))
            val extra = effects.flatMap { (type, tag) ->
                when (type) {
                    "alpha_pulse", "pulse", "chain", "sweep" -> {
                        val style = if (mode == "mix") type else options.pulseStyle
                        flashes(if (mode == "mix") windows(tag) else windows("all"), style == "sweep")
                    }
                    "grid" -> grid(windows(tag))
                    "pinwheel" -> pinwheel(tag)
                    "curve" -> curves(windows(tag), false)
                    "meteor" -> curves(windows(tag, sections.sortedByDescending { it.density }.take(3)), true)
                    "echo_trail" -> echoes(rot, x)
                    "spotlight" -> spotlight(windows(tag))
                    else -> emptyList()
                }
            }
            val useSwing = options.mainSwing || mode == "main_swing"
            return Result(JsonArray(listOf(layer(alpha, x.takeIf { useSwing }.orEmpty(), y.ifEmpty { listOf(constant(options.mainY)) }, rot.takeIf { useSwing }.orEmpty()))), extra, finalNotes, ext)
        }

        private fun parseMix(): List<Effect> = options.mixSpec.split(';').mapNotNull { entry ->
            val type = entry.substringBefore('@').trim()
            if (type.isEmpty()) null else Effect(type, entry.substringAfter('@', "all").trim().ifEmpty { "all" })
        }.ifEmpty { listOf(Effect("grid", "all"), Effect("sweep", "all"), Effect("pinwheel", "climax")) }

        private fun window(section: AutoSection) = Window(quantize(section.startTime * beatsPerSec), quantize(section.endTime * beatsPerSec))
        private fun windows(tag: String, source: List<AutoSection> = sections): List<Window> {
            val all = listOf(Window(0.0, max(1.0, last)))
            val peak = source.maxOfOrNull { it.density }?.takeIf { it != 0.0 } ?: 1.0
            return when (tag) {
                "all" -> all
                "repeat" -> (0 until max(1, last.toInt()) step 8).map { Window(it.toDouble(), min(it + 8.0, max(1.0, last))) }
                else -> source.filterIndexed { i, s -> when (tag) {
                    "climax" -> s.density >= .6 * peak
                    "intro" -> s.label == "intro/break"
                    "verse", "chorus" -> s.label == tag
                    "intro_chorus" -> s.label == "intro/break" || s.label == "chorus"
                    "altA", "altB" -> (i % 2 == 0) == (tag == "altA")
                    else -> false
                } }.map(::window).filter { it.to > it.from }.ifEmpty {
                    when (tag) {
                        "climax" -> listOf(Window(0.0, 8.0))
                        "altA", "altB" -> all
                        else -> emptyList()
                    }
                }
            }
        }

        private fun swing(): Pair<List<Event>, List<Event>> {
            val rotation = mutableListOf<Event>()
            val movement = mutableListOf<Event>()
            for (section in sections) {
                val w = window(section)
                if (w.to - w.from < 2) continue
                val left = taps.count { it.beat in w && it.x < 0 }
                val right = taps.count { it.beat in w && it.x >= 0 }
                val direction = when {
                    left > right * 1.2 -> -1
                    right > left * 1.2 -> 1
                    else -> continue
                }
                fun segment(amp: Double) = listOf(Event(w.from, w.from + 1, 0.0, amp, 6),
                    Event(w.from + 1, w.to - 1, amp, amp), Event(w.to - 1, w.to, amp, 0.0, 6))
                rotation += segment(direction * 5.0 * (.5 + section.density).coerceIn(.5, 1.6))
                movement += segment(direction * 40.0)
            }
            return rotation to movement
        }

        private fun lift(): Pair<List<Event>, List<JsonObject>> {
            val startSec = sections.maxByOrNull { it.density }?.let { it.startTime + .5 } ?: 40.0
            val from = startSec * beatsPerSec
            val to = (startSec + 12) * beatsPerSec
            val trans = min(3.0, (to - from) / 2)
            val base = options.mainY
            val up = 200.0
            val result = listOf(Event(0.0, from, base, base), Event(from, from + trans, base, up, 6),
                Event(from + trans, to - trans, up, up), Event(to - trans, to, up, base, 6), Event(to, end, base, base))
            val active = Window(from, to)
            return result to notes.map { if (beat(it.getValue("startTime")) in active) JsonObject(it + ("above" to JsonPrimitive(2))) else it }
        }

        private fun shake(times: List<Double>, amp: Double, seconds: Double = .18): List<Event> {
            val d = max(seconds * beatsPerSec, .05)
            return times.flatMap { listOf(Event(it, it + d, 0.0, amp, 24), Event(it + d, it + 2 * d, amp, -amp, 24), Event(it + 2 * d, it + 3 * d, -amp, 0.0, 24)) }
        }

        private fun addBounce(base: List<Event>, times: List<Double>): List<Event> {
            val absolute = base.ifEmpty { listOf(constant(options.mainY)) }
            val offsets = shake(times, 18.0, .14)
            if (offsets.isEmpty()) return absolute
            val boundaries = (absolute.flatMap { listOf(it.from, it.to) } + offsets.flatMap { listOf(it.from, it.to) })
                .filter { it in 0.0..end }.distinct().sorted()
            fun value(event: Event, beat: Double): Double {
                if (event.to <= event.from) return event.end
                val ratio = ((beat - event.from) / (event.to - event.from)).coerceIn(0.0, 1.0)
                return event.start + (event.end - event.start) * ratio
            }
            fun absoluteAt(beat: Double) = absolute.lastOrNull { beat >= it.from && beat < it.to }
                ?.let { value(it, beat) } ?: options.mainY
            fun offsetAt(beat: Double, terminal: Boolean = false) = offsets
                .filter { beat >= it.from && (beat < it.to || terminal && beat == it.to) }
                .sumOf { value(it, beat) }
            return boundaries.zipWithNext().mapNotNull { (from, to) ->
                if (to <= from) null else Event(
                    from, to,
                    absoluteAt(from) + offsetAt(from),
                    absoluteAt(Math.nextDown(to)) + offsetAt(Math.nextDown(to)), 
                    offsets.lastOrNull { from >= it.from && from < it.to }?.ease ?: 1,
                )
            }
        }

        private fun flashes(ranges: List<Window>, sweep: Boolean): List<JsonObject> = buildList {
            for (left in listOf(true, false)) {
                val side = if (left) "left" else "right"
                val sign = if (left) -1 else 1
                val beats = taps.filter { (it.x < 0) == left && ranges.any { w -> it.beat in w } &&
                    (!sweep || !options.sweepFlickOnly || it.type == AutoChartConfig.FLICK) }.map { it.beat }.distinct()
                if (beats.isEmpty()) continue
                val d = (if (sweep) .28 else .15) * beatsPerSec
                fun alpha(times: List<Double>) = times.flatMap { b -> listOf(Event(b - d, b, 0.0, 200.0), Event(b, b + d, 200.0, 0.0)) }
                if (sweep) {
                    add(line("侧闪线_$side", -1, alpha(beats), beats.map { b -> Event(b - d, b + d, 560.0 * sign, -560.0 * sign) }, shake(beats, 30.0), listOf(constant(80.0 * sign))))
                } else {
                    val runs = mutableListOf<MutableList<Double>>()
                    for (b in beats) {
                        val run = runs.lastOrNull()
                        if (run == null || b - run.last() > 1.2) runs += mutableListOf(b) else run += b
                    }
                    repeat(5) { i ->
                        val times = runs.mapNotNull { it.getOrNull(i) }
                        if (times.isNotEmpty()) add(line("侧闪线_${side}_${i + 1}", -1 - i, alpha(times),
                            listOf(constant(sign * (80.0 + 120.0 * i))), shake(times, 30.0), listOf(constant(45.0 * sign))))
                    }
                }
            }
        }

        private fun grid(ranges: List<Window>): List<JsonObject> = buildList {
            fun active(b: Double) = ranges.any { b in it }
            for ((i, x) in listOf(-400.0, -200.0, 0.0, 200.0, 400.0).withIndex()) {
                val alpha = mutableListOf<Event>()
                val movement = mutableListOf(constant(x))
                if (active(0.0)) alpha += fade(Window(i * 1.2, i * 1.2 + 1.6), 90.0, .4)
                for (hit in hits) if (hit.type == AutoChartConfig.FLICK && active(hit.beat) && (x == 0.0 || (x < 0) == (hit.x < 0))) {
                    val b = hit.beat
                    alpha += listOf(Event(b - .25, b + .25, 0.0, 90.0), Event(b + .25, b + .6, 90.0, 0.0))
                    movement += Event(b - .25, b + .25, 0.0, x)
                }
                val closing = max(0.0, last - 4) + i * .6
                if (active(last) && closing + 1.4 <= last) alpha += fade(Window(closing, closing + 1.4), 90.0, .35)
                add(line("竖线${i + 1}", -8 - i, alpha, movement, rot = listOf(constant(90.0))))
            }
            val holds = hits.filter { it.type == AutoChartConfig.HOLD }
            for ((i, y) in listOf(-180.0, -60.0, 60.0, 180.0).withIndex()) {
                val alpha = mutableListOf<Event>()
                val movement = mutableListOf(constant(y))
                for ((j, hit) in holds.withIndex()) if (j % 4 == i && active(hit.beat)) {
                    val b = hit.beat
                    alpha += listOf(Event(b, b + 1.2, 0.0, 90.0), Event(b + 1.2, b + 1.8, 90.0, 0.0))
                    movement += Event(b, b + 1.2, -300.0, y)
                }
                add(line("横线${i + 1}", -13 - i, alpha, y = movement))
            }
        }

        private fun pinwheel(tag: String): List<JsonObject> {
            val delay = 12 * beatsPerSec
            val base = windows(tag)
            if (base.isEmpty()) return emptyList()
            val range = if (tag == "all") base else base.filter { it.to > delay }.map { Window(max(delay, it.from), it.to) }.let {
                if (it.any { w -> abs(w.from - delay) < 1e-6 }) it else listOf(Window(delay, delay + 8)) + it
            }.distinct().take(12)
            val xs = listOf(-300, -150, 0, 150, 300)
            val types = listOf(4, 1, 4, 3, 4)
            return List(6) { i ->
                val fake = buildList {
                    for (w in range) {
                        var b = w.from
                        var j = i
                        val step = max(1.0, (w.to - w.from) / 150)
                        while (b < w.to && size < 150) {
                            add(buildJsonObject {
                                put("above", 1); put("alpha", 220); put("isFake", 1); put("positionX", xs[j % 5]); put("type", types[j % 5])
                                put("startTime", fraction(b)); put("endTime", fraction(b)); put("size", 1.0); put("speed", 1.0)
                                put("visibleTime", end); put("yOffset", 0.0)
                            })
                            b += step
                            j++
                        }
                    }
                }
                line("pinwheel${i + 1}", -3, listOf(constant(0.0)), rot = range.map { Event(it.from, it.to, 60.0 * i, 60.0 * i + 360) }, notes = fake)
            }
        }

        private fun curves(ranges: List<Window>, meteor: Boolean): List<JsonObject> = ranges.mapIndexed { i, w ->
            val n = if (meteor) 28 else 32
            val x = mutableListOf(constant(0.0))
            val y = mutableListOf(constant(0.0))
            val rot = mutableListOf(constant(0.0))
            var previous = pose(0.0, meteor)
            fun round1(v: Double) = round(v * 10) / 10
            for (j in 0 until n) {
                val next = pose((j + 1.0) / n, meteor)
                val angle = previous.angle + ((next.angle - previous.angle + 180) % 360 + 360) % 360 - 180
                val start = w.from + (w.to - w.from) * j / n
                val finish = w.from + (w.to - w.from) * (j + 1) / n
                x += Event(start, finish, round1(previous.x), round1(next.x))
                y += Event(start, finish, round1(previous.y), round1(next.y))
                rot += Event(start, finish, round1(previous.angle), round1(angle))
                previous = next.copy(angle = angle)
            }
            line("curve_traj${i + 1}", -6 - i, fade(w, if (meteor) 150.0 else 140.0, 1.0, true), x, y, rot)
        }

        private fun pose(t: Double, meteor: Boolean): Pose {
            if (!meteor) {
                val angle = PI * (1 - t)
                return Pose(430 * cos(angle), -120 + 430 * sin(angle), Math.toDegrees(angle + PI / 2))
            }
            val u = 1 - t
            fun pos(a: Double, b: Double, c: Double, d: Double) = u*u*u*a + 3*u*u*t*b + 3*u*t*t*c + t*t*t*d
            fun tangent(a: Double, b: Double, c: Double, d: Double) = 3*u*u*(b-a) + 6*u*t*(c-b) + 3*t*t*(d-c)
            return Pose(pos(-560.0, -370.0, 370.0, 560.0), pos(320.0, 110.0, -110.0, -320.0),
                Math.toDegrees(atan2(tangent(320.0, 110.0, -110.0, -320.0), tangent(-560.0, -370.0, 370.0, 560.0))))
        }

        private fun echoes(rot: List<Event>, x: List<Event>): List<JsonObject> {
            if (rot.isEmpty()) return emptyList()
            return List(3) { i ->
                val factor = 1.0 - (i + 1) * .16
                fun scale(e: Event) = e.copy(start = round(e.start * factor * 10) / 10, end = round(e.end * factor * 10) / 10)
                line("echo${i + 1}", -4 - i, listOf(constant(120.0 * (1 - (i + 1) / 4.0))), x.map(::scale), rot = rot.map(::scale))
            }
        }

        private fun spotlight(ranges: List<Window>): List<JsonObject> {
            if (ranges.isEmpty()) return emptyList()
            val selected = ranges.take(2)
            val tint = rgb(listOf(12, 12, 24))
            val ext = mapOf("scaleXEvents" to events(listOf(constant(42.0))), "scaleYEvents" to events(listOf(constant(90.0))),
                "colorEvents" to JsonArray(listOf(jsonEvent(constant(0.0), tint, tint))))
            return selected.mapIndexed { i, w -> line("spotlight${i + 1}", -20, fade(w, 110.0, 1.5), extended = ext) }
        }

        private fun colors(): JsonArray {
            val cold = listOf(70, 130, 255)
            val warm = listOf(255, 120, 70)
            if (sections.isEmpty()) return JsonArray(listOf(jsonEvent(constant(0.0).copy(ease = 6), rgb(cold), rgb(warm))))
            val peak = sections.maxOf { it.density }.takeIf { it > 0 } ?: 1.0
            return JsonArray(buildList {
                val first = window(sections.first()).from
                if (first > 0) add(jsonEvent(Event(0.0, first, 0.0, 0.0), rgb(cold), rgb(cold)))
                for (section in sections) {
                    val t = (section.density / peak).coerceIn(0.0, 1.0)
                    val c = rgb(cold.zip(warm).map { (a, b) -> round(a + (b - a) * t).toInt() })
                    val w = window(section)
                    add(jsonEvent(Event(w.from, w.to, 0.0, 0.0), c, c))
                }
            })
        }

        private fun fade(w: Window, alpha: Double, duration: Double, bezier: Boolean = false): List<Event> {
            if (w.to <= w.from) return emptyList()
            val length = min(duration, (w.to - w.from) / 2)
            val first = w.from + length
            val last = w.to - length
            return buildList {
                add(Event(w.from, first, 0.0, alpha, bezier = bezier))
                if (last > first) add(Event(first, last, alpha, alpha))
                add(Event(last, w.to, alpha, 0.0, bezier = bezier))
            }
        }

        private fun line(name: String, depth: Int, alpha: List<Event>, x: List<Event> = emptyList(), y: List<Event> = emptyList(),
                         rot: List<Event> = emptyList(), notes: List<JsonObject> = emptyList(), extended: Map<String, JsonArray> = emptyMap()) = buildJsonObject {
            val sorted = alpha.sortedBy { it.from }
            val first = sorted.firstOrNull()?.from ?: end
            val visible = if (first > 0) listOf(Event(0.0, first, 0.0, 0.0)) + sorted else sorted
            put("Group", 0); put("Name", name); put("Texture", "line.png"); put("bpmfactor", 1.0)
            put("eventLayers", JsonArray(listOf(layer(visible, x, y, rot))))
            put("extended", buildJsonObject {
                put("inclineEvents", events(listOf(Event(0.0, 1.0, 0.0, 0.0, 0))))
                extended.forEach { (key, value) -> put(key, value) }
            })
            put("father", -1); put("isCover", 1); put("numOfNotes", notes.size); put("notes", JsonArray(notes)); put("zOrder", depth)
            for ((key, value) in listOf("alpha" to 1.0, "pos" to 1.0, "size" to 1.0, "skew" to 0.0, "y" to 1.0)) {
                put("${key}Control", JsonArray(listOf(0.0, 9999999.0).map {
                    buildJsonObject { put("easing", 1); put(key, value); put("x", it) }
                }))
            }
        }

        private fun layer(alpha: List<Event>, x: List<Event>, y: List<Event>, rot: List<Event>) = buildJsonObject {
            put("alphaEvents", events(alpha.ifEmpty { listOf(constant(255.0)) }))
            put("moveXEvents", events(x.ifEmpty { listOf(constant(0.0)) }))
            put("moveYEvents", events(y.ifEmpty { listOf(constant(0.0)) }))
            put("rotateEvents", events(rot.ifEmpty { listOf(constant(0.0)) }))
            put("speedEvents", JsonArray(listOf(buildJsonObject {
                put("startTime", fraction(0.0)); put("endTime", fraction(end)); put("linkgroup", 0)
                put("start", AutoChartConfig.DEFAULT_SPEED); put("end", AutoChartConfig.DEFAULT_SPEED)
            })))
        }

        private fun constant(value: Double) = Event(0.0, end, value, value)
        private fun events(values: List<Event>) = JsonArray(values.sortedWith(compareBy<Event> { max(0.0, it.from) }.thenBy { it.to }).map { jsonEvent(it) })
        private fun jsonEvent(e: Event, start: JsonElement = JsonPrimitive(e.start), finish: JsonElement = JsonPrimitive(e.end)) = buildJsonObject {
            put("startTime", fraction(e.from)); put("endTime", fraction(e.to)); put("easingType", e.ease)
            put("start", start); put("end", finish); put("linkgroup", 0); put("easingLeft", 0.0); put("easingRight", 1.0)
            put("bezier", if (e.bezier) 1 else 0)
            put("bezierPoints", JsonArray(listOf(0.0, 0.0, if (e.bezier) 1.0 else 0.0, if (e.bezier) 1.0 else 0.0).map(::JsonPrimitive)))
        }
        private fun rgb(values: List<Int>) = JsonArray(values.map(::JsonPrimitive))
        private fun quantize(beat: Double) = round(beat * tpb) / tpb
        private fun fraction(beat: Double): JsonArray {
            val ticks = round(beat.coerceAtLeast(0.0) * tpb).toLong()
            val remainder = (ticks % tpb).toInt()
            var a = tpb
            var b = remainder
            while (b > 0) { val r = a % b; a = b; b = r }
            return JsonArray(listOf(JsonPrimitive(ticks / tpb), JsonPrimitive(remainder / a), JsonPrimitive(tpb / a)))
        }
        private fun beat(value: JsonElement): Double {
            val f = value.jsonArray
            return f[0].jsonPrimitive.double + f[1].jsonPrimitive.double / f[2].jsonPrimitive.double
        }
    }
}
