package com.touchbridge.rpe.domain.autochart

import com.touchbridge.rpe.domain.autochart.AutoChartConfig.DRAG
import com.touchbridge.rpe.domain.autochart.AutoChartConfig.FLICK
import com.touchbridge.rpe.domain.autochart.AutoChartConfig.HOLD
import com.touchbridge.rpe.domain.autochart.AutoChartConfig.TAP
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

object Charter {
    fun generate(analysis: AutoAnalysis, options: AutoChartOptions, tier: String = options.tier): List<JsonObject> =
        generate(
            analysis.points.map { Point(it.timeSec, it.beat, it.beatTick, it.probability) },
            analysis.sections.map { Section(it.startTime, it.endTime, it.label, it.density) },
            analysis.bpm, analysis.ticksPerBeat, analysis.bands, analysis.onsetNorm, analysis.rmsNorm,
            analysis.sr, options, tier, analysis.hopLength,
        )

    internal fun generate(
        points: List<Point>, sections: List<Section>, bpm: Double, ticksPerBeat: Int,
        bands: Map<String, DoubleArray>, onsetNorm: DoubleArray, rmsNorm: DoubleArray,
        sampleRate: Int, options: AutoChartOptions, tier: String = options.tier,
        hopLength: Int = AutoChartConfig.HOP_LENGTH,
    ): List<JsonObject> = Engine(
        points, sections, bpm, ticksPerBeat, bands, onsetNorm, rmsNorm, sampleRate,
        hopLength, AutoChartConfig.preset(tier, options), options,
    ).generate()

    internal data class Point(val timeSec: Double, val beat: Double, val beatTick: Int, val probability: Double)
    internal data class Section(val startTime: Double, val endTime: Double, val label: String, val density: Double)
    private data class Candidate(val score: Double, val tick: Int, val timeSec: Double)
    private data class Cursor(var position: Int, var direction: Int)
    private data class Tri(var position: Int = 0, var startHand: Int = 0, var switch: Int = 0, var leftIndex: Int = 0, var base: Int = 0)
    private data class Block(val occurrence: Int, val stairDirection: Int) {
        val stair = mutableMapOf<Int, Cursor>()
        val zig = mutableMapOf<Int, Cursor>()
        var drag: Cursor? = null
        val tri = Tri()
        var confirmation = 0
        var offsetLanes: List<Int>? = null
    }
    private data class RawSegment(val points: List<Point>, val key: String, val sectionIndex: Int)
    private data class PhraseRun(val label: String, val segments: MutableList<RawSegment>)
    private data class Segment(val points: List<Point>, val pattern: String, val bias: Int, val key: String, val block: Block)
    private data class Note(val start: Int, val end: Int, val lane: Int, var type: Int)

    private val emphasisPatterns = setOf("stream", "jack", "stair", "stair_up", "stair_down", "burst", "alternate", "zigzag", "roundtrip")
    private val denseChordPatterns = (emphasisPatterns - "jack") + "syncop"
    private val dragUnits = setOf("dragchain", "drag_sea", "snake_tap", "drag", "syncop")
    private val directions = listOf(1, -1)
    private val hands = listOf(0, 1)
    private val snakeConfirm = listOf("hold", "tap", "double", "flick", "double", "tap", "hold", "double")
    private val seaConfirm = listOf("hold", "tap", "double", "flick", "double", "hold", "tap", "double")

    private class Engine(
        private val points: List<Point>, private val sections: List<Section>, private val bpm: Double,
        ticksPerBeat: Int, private val bands: Map<String, DoubleArray>, private val onsetNorm: DoubleArray,
        private val rmsNorm: DoubleArray, private val sr: Int, private val hopLength: Int, val diff: AutoChartConfig.Preset,
        options: AutoChartOptions,
    ) {
        private val ticksPerBeat = ticksPerBeat.takeIf { it > 0 } ?: 480
        private val rng = CharterRandom(options.seed)
        private val densityMult = if (options.density == 0.0) 1.0 else options.density
        private val lanes = diff.lanes
        private val left = lanes.take(lanes.size / 2)
        private val right = lanes.drop(lanes.size / 2)
        private val span = abs(lanes.last() - lanes.first())
        private val maxChord = minOf(diff.maxChord, lanes.size.coerceAtLeast(1))
        private val holdCooldownBeats = AutoChartConfig.HOLD_COOLDOWN_SEC * bpm / 60.0
        private val anchors = points.sortedBy { it.beatTick }
        private var lastTime = -1e9
        private var lastHoldTime = -1e9
        private var lastHoldHand: Int? = null
        private var lastHand: Int? = null
        private val lastLaneHand = arrayOfNulls<Int>(2)
        private val laneLastTime = lanes.associateWith { -1e9 }.toMutableMap()
        private val laneCount = lanes.associateWith { 0 }.toMutableMap()
        private val laneStreak = arrayOf(0, 0)
        private val handHoldUntil = DoubleArray(2)
        private var bias = 1
        private var jackLane = 0
        private var jackHand = 0
        private var jackRemaining = 0
        private val sectionRuns = run {
            val counts = mutableMapOf<String, Int>()
            var previous: String? = null
            sections.map {
                if (it.label != previous) counts[it.label] = counts.getOrDefault(it.label, 0) + 1
                previous = it.label
                counts.getValue(it.label)
            }
        }

        private fun frame(timeSec: Double) = (timeSec * sr / hopLength).toInt()
        private fun onsetAt(timeSec: Double) = onsetNorm.getOrNull(frame(timeSec))?.toDouble() ?: 0.0
        private fun dominantBand(timeSec: Double): String {
            val f = frame(timeSec)
            return bands.entries.maxByOrNull { (_, values) ->
                values.getOrNull(minOf(f, values.lastIndex))?.toDouble() ?: 0.0
            }?.key ?: "low"
        }

        private inline fun lowerBound(target: Double, value: (Point) -> Double): Int {
            var lo = 0
            var hi = anchors.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (value(anchors[mid]) < target) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun timeAtTick(tick: Int): Double {
            if (anchors.isEmpty()) return tick * 60.0 / maxOf(bpm, 1.0)
            if (tick <= anchors.first().beatTick) return anchors.first().timeSec
            if (tick >= anchors.last().beatTick) return anchors.last().timeSec
            val i = lowerBound(tick.toDouble()) { it.beatTick.toDouble() }
            val a = anchors[i - 1]
            val b = anchors[i]
            if (a.beatTick == b.beatTick) return a.timeSec
            val fraction = (tick - a.beatTick).toDouble() / (b.beatTick - a.beatTick)
            return a.timeSec + (b.timeSec - a.timeSec) * fraction
        }

        private fun tickAtTime(timeSec: Double): Int {
            if (anchors.isEmpty()) return round(timeSec * maxOf(bpm, 1.0) / 60.0).toInt()
            if (timeSec <= anchors.first().timeSec) return anchors.first().beatTick
            if (timeSec >= anchors.last().timeSec) return anchors.last().beatTick
            val i = lowerBound(timeSec) { it.timeSec }
            val a = anchors[i - 1]
            val b = anchors[i]
            if (a.timeSec == b.timeSec) return a.beatTick
            val fraction = (timeSec - a.timeSec) / (b.timeSec - a.timeSec)
            return round(a.beatTick + (b.beatTick - a.beatTick) * fraction).toInt()
        }

        private fun detectSustain(timeSec: Double): Double {
            val frame = frame(timeSec)
            if (frame !in rmsNorm.indices) return 0.0
            var sustained = 0
            var gap = 0
            val maxOffset = minOf(AutoChartConfig.SUSTAIN_RMS_MIN_FRAMES + 32, rmsNorm.size - frame)
            for (offset in 1 until maxOffset) {
                if (rmsNorm[frame + offset].toDouble() >= diff.rmsSustain) {
                    sustained++
                    gap = 0
                } else if (++gap > AutoChartConfig.SUSTAIN_MAX_GAP) break
            }
            return if (sustained >= AutoChartConfig.SUSTAIN_RMS_MIN_FRAMES) sustained * hopLength.toDouble() / sr else 0.0
        }

        private fun sectionIndex(timeSec: Double): Int = sections.indexOfFirst { timeSec >= it.startTime && timeSec < it.endTime }
            .takeIf { it >= 0 } ?: (sections.size - 1).coerceAtLeast(0)

        private fun densityKey(density: Double) = when {
            density <= 0.3 -> "sparse"
            density <= 0.6 -> "mid"
            else -> "dense"
        }

        private fun space(points: List<Point>): List<Point> = buildList {
            val leadIn = AutoChartConfig.LEAD_IN_BEATS * 60.0 / maxOf(bpm, 1.0)
            var previousTime = -1e9
            var previousBeat = -1e18
            for (p in points) {
                if (p.timeSec < leadIn || p.timeSec - previousTime < diff.minGapMs / 1000.0 ||
                    p.beat - previousBeat < diff.minGapBeats) continue
                add(p)
                previousTime = p.timeSec
                previousBeat = p.beat
            }
        }

        private fun selectFlat(): List<Point> {
            val perSection = points.sortedBy { it.timeSec }.groupBy { sectionIndex(it.timeSec) }
            val selected = sections.flatMapIndexed { i, section ->
                val pts = perSection[i].orEmpty()
                val beats = maxOf(1.0, (section.endTime - section.startTime) * bpm / 60.0)
                val keepPerBeat = round(diff.keepRatio * section.density * pts.size / beats).toInt().coerceAtLeast(1)
                pts.sortedBy { it.beat }.groupBy { floor(it.beat + 1e-6).toInt() }.values.flatMap { group ->
                    group.sortedByDescending { it.probability }.take(keepPerBeat)
                }
            }
            return space(selected.sortedBy { it.timeSec })
        }

        private fun densityFill(selected: List<Point>): List<Point> {
            val gapSec = diff.minGapMs / 1000.0
            val existing = selected.mapTo(mutableSetOf()) { it.beatTick }
            val extras = mutableListOf<Point>()
            for ((i, section) in sections.withIndex()) {
                val start = section.startTime
                val end = section.endTime
                val key = densityKey(section.density)
                val progression = if (key == "dense" && section.label == "chorus") 1.0 + 0.18 * (sectionRuns[i] - 1) else 1.0
                var npb = diff.targetNpb.getOrDefault(key, 1.0) * densityMult * progression
                if (i == sections.lastIndex) npb *= 1.10
                val beats = maxOf(0.0, (end - start) * bpm / 60.0)
                val target = minOf(round(diff.maxNps * (end - start)).toInt(), round(npb * beats * diff.fillRatio).toInt())
                val base = selected.filter { it.timeSec >= start && it.timeSec < end }
                val need = target - base.size
                if (need <= 0) continue
                val qpb = if (npb >= 0.5) round(npb).toInt().coerceAtLeast(1) else 1
                val gridSub = maxOf(qpb, minOf(diff.subdivision, 16))
                val step = round(ticksPerBeat.toDouble() / gridSub).toInt().coerceAtLeast(1)
                val startTick = tickAtTime(start)
                val endTick = tickAtTime(end)
                val basePerBeat = base.groupingBy { floor(maxOf(0.0, (it.timeSec - start) * bpm / 60.0 + 1e-6)).toInt() }.eachCount()
                val kept = mutableListOf<Candidate>()
                var last: Candidate? = null
                for (bi in 0..ceil(beats).toInt()) {
                    if (kept.size >= need) break
                    val quota = qpb - basePerBeat.getOrDefault(bi, 0)
                    if (quota <= 0) continue
                    val beatLo = startTick + bi * ticksPerBeat
                    val beatHi = beatLo + ticksPerBeat
                    val candidates = mutableListOf<Candidate>()
                    var tick = ((beatLo + step - 1) / step) * step
                    while (tick < minOf(beatHi, endTick)) {
                        if (tick !in existing) {
                            val timeSec = timeAtTick(tick)
                            val strength = onsetAt(timeSec)
                            if (strength >= 0.10 && base.none { abs(timeSec - it.timeSec) < gapSec }) {
                                candidates += Candidate(strength, tick, timeSec)
                            }
                        }
                        tick += step
                    }
                    var taken = 0
                    for (candidate in candidates.sortedByDescending { it.score }) {
                        if (taken >= quota || kept.size >= need) break
                        if (last != null && (candidate.timeSec - last.timeSec < gapSec ||
                            (diff.minGapBeats > 0 && candidate.tick.toDouble() / ticksPerBeat - last.tick.toDouble() / ticksPerBeat < diff.minGapBeats))) continue
                        kept += candidate
                        existing += candidate.tick
                        last = candidate
                        taken++
                    }
                }
                extras += kept.map { Point(it.timeSec, it.tick.toDouble() / ticksPerBeat, it.tick, (0.3 + it.score).coerceIn(0.2, 1.0)) }
            }
            return space((selected + extras).sortedBy { it.timeSec })
        }

        private fun classifyType(timeSec: Double): Int {
            val strength = onsetAt(timeSec)
            val band = dominantBand(timeSec)
            val accent = strength > 0.35 || (strength > 0.20 && band == "high")
            if (band == "low" && strength > 0.35 && diff.flickWeight > 0.01) return FLICK
            if (accent && diff.flickWeight > 0.01 && rng.nextDouble() < 0.60 * minOf(1.0, diff.flickWeight * 3.0)) return FLICK
            if (band == "high" && !accent && !diff.disableDrag && rng.nextDouble() < diff.dragWeight * 0.6) return DRAG
            if (!accent && !diff.disableDrag && rng.nextDouble() < diff.dragWeight * 0.6) return DRAG
            val tw = diff.tapWeight + diff.holdWeight
            val r = rng.nextDouble()
            return when {
                r < tw -> TAP
                r < tw + diff.flickWeight -> FLICK
                !diff.disableDrag -> DRAG
                else -> TAP
            }
        }

        private fun pool(hand: Int) = if (hand == 0) left else right
        private fun preferredHand() = if (bias < 0) 0 else 1
        private fun chooseHand(timeSec: Double): Int {
            val previous = lastHand
            if (previous != null && (timeSec - lastTime) * 1000.0 < diff.rollGapMs) return 1 - previous
            if (left.isEmpty() || right.isEmpty()) return rng.choice(hands)
            val lrest = left.maxOf { timeSec - laneLastTime.getValue(it) }
            val rrest = right.maxOf { timeSec - laneLastTime.getValue(it) }
            return when {
                lrest - rrest > 300.0 -> 0
                rrest - lrest > 300.0 -> 1
                else -> rng.choice(hands)
            }
        }

        private fun choosePatternHand(timeSec: Double): Int {
            val previous = lastHand
            return if (previous != null && (timeSec - lastTime) * 1000.0 < diff.alternateMs) 1 - previous else chooseHand(timeSec)
        }

        private fun idle(timeSec: Double, lane: Int) = (timeSec - laneLastTime.getValue(lane)) * 1000.0
        private fun pickLane(timeSec: Double, hand: Int): Int {
            val pool = pool(hand)
            val last = lastLaneHand[hand]
            val available = pool.filter { idle(timeSec, it) >= diff.jackMs }.ifEmpty { pool }
            return available.filter { it != last }.ifEmpty { available }.map { lane ->
                val idle = idle(timeSec, lane)
                val distance = last?.let { abs(lane - it) } ?: 0
                val penalty = if (lane == last && idle < diff.alternateMs) 420.0 else 0.0
                lane to (-idle * 0.6 + distance * 0.35 + rng.nextDouble() * span * 0.12 + laneCount.getValue(lane) * 0.015 + penalty)
            }.minByOrNull { it.second }!!.first
        }

        private fun pickAdjacent(timeSec: Double, hand: Int): Int {
            val pool = pool(hand)
            val last = lastLaneHand[hand]
            val index = pool.indexOf(last)
            val available = (if (index >= 0) listOf(pool[(index - 1).coerceAtLeast(0)], pool[(index + 1).coerceAtMost(pool.lastIndex)]).distinct() else pool)
                .filter { it != last }.ifEmpty { pool }
            return rng.choice(available.filter { idle(timeSec, it) >= diff.jackMs }.ifEmpty { available.sortedByDescending { last?.let { l -> abs(it - l) } ?: 0 } })
        }

        private fun pickAlternate(timeSec: Double, hand: Int): Int {
            val pool = pool(hand)
            val other = lastLaneHand[1 - hand]
            val last = lastLaneHand[hand]
            val ordered = if (other != null && rng.nextDouble() < 0.5) pool.sortedBy { abs(it - other) } else pool
            val available = ordered.filter { it != last }.ifEmpty { ordered }
            return available.filter { idle(timeSec, it) >= diff.jackMs }.ifEmpty { available }.map { lane ->
                val idle = idle(timeSec, lane)
                val distance = last?.let { abs(lane - it) } ?: 0
                val penalty = if (lane == last && idle < diff.alternateMs) 420.0 else 0.0
                lane to (-idle * 0.5 + distance * 0.3 + rng.nextDouble() * span * 0.2 + laneCount.getValue(lane) * 0.015 + penalty)
            }.maxByOrNull { it.second }!!.first
        }

        private fun chordLanes(mirror: Boolean = false): Pair<Int, Int> {
            if (left.isEmpty() || right.isEmpty()) return lanes.first() to lanes.last()
            if (mirror && lanes.size >= 4) {
                val i = rng.nextInt(lanes.size / 2)
                return lanes[i] to lanes[lanes.lastIndex - i]
            }
            if (lanes.size >= 6) return rng.choice(left) to rng.choice(right)
            return if (rng.nextDouble() < 0.25) left.first() to right.last() else left.last() to right.first()
        }

        private fun laneForHand(hand: Int): Int = pool(hand).minByOrNull { laneCount.getValue(it) } ?: lanes.first()
        private fun register(timeSec: Double, hand: Int, lane: Int) {
            lastTime = timeSec
            lastHand = hand
            laneStreak[hand] = if (lastLaneHand[hand] == lane) laneStreak[hand] + 1 else 0
            lastLaneHand[hand] = lane
            laneLastTime[lane] = timeSec
            laneCount[lane] = laneCount.getValue(lane) + 1
        }

        private fun freeHands(beat: Double) = hands.filter { beat >= handHoldUntil[it] }
        private fun pickHoldBeats(sustain: Double) = minOf(rng.choice(when {
            sustain > 0.65 -> listOf(0.5, 0.75, 1.0, 1.5)
            sustain > 0.45 -> listOf(0.375, 0.5, 0.75)
            else -> AutoChartConfig.HOLD_SHORT_TIER
        }), AutoChartConfig.HOLD_MAX_BEATS)

        private fun weightedMotif(motifs: List<AutoChartConfig.Motif>): AutoChartConfig.Motif {
            val configured = motifs.map { diff.motifWeights[it.name] }
            val weights = if (configured.all { it != null } && configured.filterNotNull().sum() > 0.0) configured.filterNotNull()
                else motifs.map { if (it.name.contains("drag", ignoreCase = true)) 0.30 else 1.0 }
            val r = rng.nextDouble() * weights.sum()
            var acc = 0.0
            for ((i, motif) in motifs.withIndex()) {
                acc += weights[i]
                if (r < acc) return motif
            }
            return motifs.last()
        }

        private fun motifForRun(label: String, key: String, occurrence: Int): List<String> {
            val library = diff.customMotifs.ifEmpty { AutoChartConfig.motifLibrary }
            val pool = library[label].orEmpty().ifEmpty { library[key].orEmpty() }
            if (pool.isEmpty()) return diff.patterns.ifEmpty { listOf("roll") }
            val tier = if (diff.level >= 12 && occurrence >= 2) 2 else 1
            val motif = weightedMotif(pool.filter { it.tier == tier }.ifEmpty { pool })
            return motif.units.filterNot { (maxChord < 3 && it == "triple") || ((lanes.size < 4 || diff.quadProb <= 0) && it == "quad") }
                .map {
                    when {
                        lanes.size < 6 && (it == "mirror_chord" || it == "mirror") -> "chord"
                        diff.disableDrag && it in dragUnits -> "flow"
                        else -> it
                    }
                }.ifEmpty { listOf("flow") }
        }

        private fun planSegments(points: List<Point>): List<Segment> {
            val raw = mutableListOf<RawSegment>()
            var i = 0
            while (i < points.size) {
                val secIndex = sectionIndex(points[i].timeSec)
                val key = densityKey(sections[secIndex].density)
                var end = i + 1
                while (end < points.size && sectionIndex(points[end].timeSec) == secIndex) end++
                while (i < end) {
                    val start = i
                    val endBeat = points[i].beat + diff.segmentBeats.coerceAtLeast(1)
                    i++
                    while (i < end && points[i].beat < endBeat) i++
                    raw += RawSegment(points.subList(start, i), key, secIndex)
                }
            }
            val runs = mutableListOf<PhraseRun>()
            for (segment in raw) {
                val label = sections[segment.sectionIndex].label
                if (runs.lastOrNull()?.label == label) runs.last().segments.add(segment)
                else runs.add(PhraseRun(label, mutableListOf(segment)))
            }
            val occurrences = mutableMapOf<String, Int>()
            return runs.flatMap { run ->
                val occurrence = occurrences.getOrDefault(run.label, 0) + 1
                occurrences[run.label] = occurrence
                val key = run.segments.first().key
                val units = motifForRun(run.label, key, occurrence)
                val runBias = bias
                rng.nextDouble() // The reference consumes a block seed before building each run.
                val block = Block(occurrence, runBias)
                bias = -bias
                run.segments.mapIndexed { j, s -> Segment(s.points, units[j % units.size], runBias, key, block) }
            }
        }

        private fun advanceCursor(pool: List<Int>, cursor: Cursor): Int {
            val lane = pool[cursor.position]
            cursor.position += cursor.direction
            if (cursor.position >= pool.size) {
                cursor.position = (pool.size - 2).coerceAtLeast(0)
                cursor.direction = -1
            } else if (cursor.position < 0) {
                cursor.position = 1.coerceAtMost(pool.lastIndex)
                cursor.direction = 1
            }
            return lane
        }

        private fun nextStair(hand: Int, direction: Int, block: Block): Int {
            val pool = pool(hand)
            if (pool.size <= 1) return pool.first()
            val cursor = block.stair.getOrPut(hand) { Cursor(pool.indices.minByOrNull { laneCount.getValue(pool[it]) }!!, direction) }
            return advanceCursor(pool, cursor)
        }

        private fun nextZig(hand: Int, block: Block): Int {
            val pool = pool(hand)
            if (pool.size <= 1) return pool.first()
            val cursor = block.zig.getOrPut(hand) { Cursor(pool.indices.minByOrNull { laneCount.getValue(pool[it]) }!!, rng.choice(directions)) }
            return advanceCursor(pool, cursor)
        }

        private fun nextDrag(block: Block): Int {
            if (lanes.size <= 1) return lanes.first()
            val cursor = block.drag ?: Cursor(lanes.indices.minByOrNull { laneCount.getValue(lanes[it]) }!!, rng.choice(directions)).also { block.drag = it }
            return advanceCursor(lanes, cursor)
        }

        private fun nextJack(timeSec: Double, free: List<Int>): Pair<Int, Int> {
            if (jackRemaining <= 0) {
                val previous = lastHand
                jackHand = if (previous != null && 1 - previous in free) 1 - previous else rng.choice(free)
                jackLane = pickLane(timeSec, jackHand)
                jackRemaining = 2 + rng.nextInt(minOf(diff.jackMax, 3) - 1)
            } else if (jackHand !in free) {
                jackHand = free.first()
                jackLane = pickLane(timeSec, jackHand)
            }
            jackRemaining--
            return jackHand to jackLane
        }

        private fun triStep(block: Block): Pair<Int, Int> {
            val tri = block.tri
            val step = tri.position % 3
            tri.position = step + 1
            if (step == 0) {
                val hand = tri.startHand
                tri.startHand = 1 - hand
                tri.switch = hand
                val pool = pool(hand).ifEmpty { lanes }
                val index = tri.leftIndex % pool.size
                tri.base = index
                tri.leftIndex = (index + 1) % pool.size
                return hand to pool[index]
            }
            val hand = (tri.switch + step) % 2
            val pool = pool(hand).ifEmpty { lanes }
            val base = tri.base % pool.size
            return hand to if (step == 1) pool[pool.lastIndex - base] else pool[(base + 1) % pool.size]
        }

        private fun triLane(hand: Int, block: Block) = if (hand == 0) left[block.tri.leftIndex % left.size] else right.first()
        private fun crossLane(hand: Int, timeSec: Double) = if (lanes.size >= 6 && rng.nextDouble() < 0.35) {
            if (hand == 0) right.first() else left.last()
        } else pickAlternate(timeSec, hand)

        private fun laneForPattern(pattern: String, hand: Int, timeSec: Double, block: Block) = when (pattern) {
            "tri_alt", "2plus3" -> triLane(hand, block)
            "cross_alt" -> crossLane(hand, timeSec)
            "stair_up" -> nextStair(hand, 1, block)
            "stair_down" -> nextStair(hand, -1, block)
            "stair" -> nextStair(hand, block.stairDirection, block)
            "zigzag", "roundtrip" -> nextZig(hand, block)
            "stream", "burst" -> pickAdjacent(timeSec, hand)
            "alternate", "syncop" -> pickAlternate(timeSec, hand)
            "dragchain", "drag_sea", "snake_tap" -> nextDrag(block)
            else -> pickLane(timeSec, hand)
        }

        private val subdivision get() = maxOf(8, diff.subdivision)
        private fun beatSlot(p: Point) = round(Math.floorMod(p.beatTick, ticksPerBeat).toDouble() / ticksPerBeat * subdivision).toInt()
        private fun downbeat(p: Point) = p.beatTick % ticksPerBeat == 0
        private fun syncopType(p: Point) = when {
            beatSlot(p) % 2 == 1 && !diff.disableDrag -> DRAG
            onsetAt(p.timeSec) > 0.4 && rng.nextDouble() < 0.5 -> FLICK
            else -> TAP
        }

        private fun makeNote(p: Point, type: Int, lane: Int, holdBeats: Double? = null): Note {
            val end = if (type == HOLD) p.beatTick + round((holdBeats ?: rng.choice(AutoChartConfig.HOLD_SHORT_TIER)) * ticksPerBeat).toInt() else p.beatTick
            return Note(p.beatTick.coerceAtLeast(0), end.coerceAtLeast(0), lane, type)
        }

        private fun emit(segment: Segment, notes: MutableList<Note>) {
            val (chunk, pattern, runBias, densityKey, block) = segment
            val progression = 1.0 + 0.20 * (block.occurrence - 1).coerceAtLeast(0)
            val holdBias = pattern == "holdleft" || pattern == "holdright"
            val preferred = preferredHand()
            val dense = densityKey == "dense"
            jackRemaining = 0
            for (p in chunk) {
                val timeSec = p.timeSec
                val sustain = detectSustain(timeSec)
                val free = freeHands(p.beat)
                fun place(type: Int, hand: Int, lane: Int, hb: Double? = null) {
                    notes += makeNote(p, type, lane, hb)
                    register(timeSec, hand, lane)
                }
                fun chord(type: Int, pair: Pair<Int, Int>) {
                    place(type, 0, pair.first)
                    place(type, 1, pair.second)
                }
                fun hold(hand: Int, lane: Int, beats: Double) {
                    handHoldUntil[hand] = p.beat + beats + holdCooldownBeats
                    place(HOLD, hand, lane, beats)
                }
                fun chooseFreeHand() = choosePatternHand(timeSec).takeIf { it in free } ?: free.first()
                fun tapOrFlick() = if (rng.nextDouble() < 0.5) TAP else FLICK

                when (pattern) {
                    "drag_sea", "snake_tap" -> {
                        if (free.isEmpty()) continue
                        val hand = chooseFreeHand()
                        val other = 1 - hand
                        val lane = nextDrag(block)
                        val confirmation = if (beatSlot(p) % maxOf(1, subdivision / 2) == 0) {
                            val sequence = if (pattern == "snake_tap") snakeConfirm else seaConfirm
                            val index = block.confirmation % sequence.size
                            block.confirmation = index + 1
                            sequence[index]
                        } else null
                        if (confirmation == "hold" && !diff.disableHold) {
                            hold(hand, lane, pickHoldBeats(sustain))
                            if (free.size == 2) place(tapOrFlick(), other, laneForHand(other))
                            continue
                        }
                        place(if (diff.disableDrag) TAP else DRAG, hand, lane)
                        when (confirmation) {
                            "tap" -> notes += makeNote(p, TAP, lane)
                            "double" -> if (free.size == 2) place(tapOrFlick(), other, laneForHand(other))
                            "flick" -> notes += makeNote(p, FLICK, lane)
                        }
                        continue
                    }
                    "tri_alt", "2plus3" -> {
                        if (free.isEmpty()) continue
                        if (pattern == "2plus3" && downbeat(p) && free.size == 2) {
                            val pair = chordLanes(rng.nextDouble() < 0.4)
                            block.tri.position = 0
                            chord(TAP, pair)
                        } else {
                            val (hand, lane) = triStep(block)
                            if (hand in free) place(TAP, hand, lane) else place(TAP, free.first(), triLane(free.first(), block))
                        }
                        continue
                    }
                    "cross_alt" -> {
                        if (free.isEmpty()) continue
                        val hand = chooseFreeHand()
                        place(TAP, hand, crossLane(hand, timeSec))
                        continue
                    }
                }

                val previousGapMs = if (lastTime > -1e8) (timeSec - lastTime) * 1000.0 else 1e9
                val minSustain = if (dense) 0.28 else 0.18
                var holdProbability = when {
                    sustain > 0.55 -> 0.6
                    sustain > 0.4 -> 0.42
                    else -> 0.22
                }
                if (holdBias) holdProbability = maxOf(holdProbability, 0.4)
                if (dense) holdProbability *= 0.25
                val breathe = previousGapMs > 320.0 || sustain > 0.55
                if (!diff.disableHold && sustain > minSustain && free.isNotEmpty() && breathe && timeSec - lastHoldTime > 1.0 && rng.nextDouble() < holdProbability) {
                    val lastHold = lastHoldHand
                    val hand = when {
                        holdBias -> (if (runBias < 0) 0 else 1).takeIf { it in free } ?: free.first()
                        lastHold == null -> preferred.takeIf { it in free } ?: rng.choice(free)
                        else -> (1 - lastHold).takeIf { it in free } ?: free.first()
                    }
                    lastHoldHand = hand
                    val other = 1 - hand
                    val beats = pickHoldBeats(sustain)
                    val lane = laneForHand(hand)
                    hold(hand, lane, beats)
                    lastHoldTime = timeSec
                    if (other in free && sustain > 0.6 && rng.nextDouble() < 0.10) {
                        val otherLane = laneForHand(other)
                        val alt = pool(other).filter { it != lane && it != otherLane }
                        hold(other, if (otherLane == lane && alt.isNotEmpty()) rng.choice(alt) else otherLane, beats)
                        continue
                    }
                    if (other in free && rng.nextDouble() < 0.45) {
                        val type = if (onsetAt(timeSec) > 0.5 && rng.nextDouble() < 0.5) FLICK else TAP
                        val otherLane = pickLane(timeSec, other)
                        val alt = pool(other).filter { it != lane && it != otherLane }
                        place(type, other, if (otherLane == lane && alt.isNotEmpty()) rng.choice(alt) else otherLane)
                    }
                    if (beats >= 1.0 && freeHands(p.beat + beats).isNotEmpty() && rng.nextDouble() < 0.2) {
                        val endBeat = p.beat + beats
                        val endTick = round(endBeat * ticksPerBeat).toInt()
                        val endTime = timeAtTick(endTick)
                        val endLane = laneForHand(other)
                        if (endLane != lane) {
                            notes += makeNote(Point(endTime, endBeat, endTick, 0.6), TAP, endLane)
                            register(endTime, other, endLane)
                        }
                    }
                    continue
                }
                if (free.isEmpty()) continue

                val type = when {
                    pattern == "syncop" -> syncopType(p)
                    pattern == "dragchain" -> if (rng.nextDouble() < 0.85) DRAG else classifyType(timeSec)
                    pattern in emphasisPatterns -> if (diff.flickWeight > 0.01 && onsetAt(timeSec) > 0.55 &&
                        rng.nextDouble() < 0.55 * minOf(1.0, diff.flickWeight * 3.0)) FLICK else TAP
                    else -> classifyType(timeSec)
                }
                val tapOrFlick = type == TAP || type == FLICK
                if (dense && pattern in denseChordPatterns && downbeat(p) && lanes.size >= 4 && free.size == 2 && tapOrFlick && rng.nextDouble() < 0.6) {
                    chord(type, chordLanes(rng.nextDouble() < 0.4))
                    continue
                }
                if ((pattern == "chord" || pattern == "mirror" || pattern == "mirror_chord") && lanes.size >= 4 && tapOrFlick && free.size == 2) {
                    val probability = if (downbeat(p)) 0.9 else 0.0
                    if (p.probability > 0.35 && rng.nextDouble() < probability) {
                        chord(type, chordLanes(pattern == "mirror_chord"))
                        continue
                    }
                }
                if (pattern == "offsetchord" && lanes.size >= 4 && tapOrFlick) {
                    if (downbeat(p) && free.size == 2 && rng.nextDouble() < 0.75) {
                        val pair = chordLanes(rng.nextDouble() < 0.5)
                        block.offsetLanes = listOf(pair.first, pair.second)
                        chord(type, pair)
                    } else {
                        val hand = chooseFreeHand()
                        val lane = block.offsetLanes?.let {
                            val target = rng.choice(it)
                            pool(hand).map { l -> l to (abs(l - target) + rng.nextDouble() * span * 0.12) }.minByOrNull { it.second }!!.first
                        } ?: pickLane(timeSec, hand)
                        place(TAP, hand, lane)
                    }
                    continue
                }
                if (pattern == "triple" && maxChord >= 3 && lanes.size >= 3 && tapOrFlick && free.size == 2 && downbeat(p) && p.probability > 0.7 &&
                    rng.nextDouble() < 0.25 * progression) {
                    val triple = if (rng.nextDouble() < 0.5) listOf(left.last(), left.first(), right.first()) else listOf(left.last(), right.first(), right.last())
                    for (lane in triple) place(type, if (lane in left) 0 else 1, lane)
                    continue
                }
                if (pattern == "quad" && lanes.size >= 4 && tapOrFlick && downbeat(p) && free.size == 2 && p.probability > 0.7 &&
                    rng.nextDouble() < diff.quadProb * 1.6) {
                    val quad = rng.sampleTwo(left).sorted() + rng.sampleTwo(right).sorted()
                    for (lane in quad) notes += makeNote(p, type, lane)
                    for (lane in quad) laneLastTime[lane] = timeSec
                    register(timeSec, 0, quad.first())
                    register(timeSec, 1, quad.last())
                    continue
                }

                val (hand, picked) = if (pattern == "jack") nextJack(timeSec, free) else {
                    val hand = if (free.size == 1) free.first() else chooseFreeHand()
                    hand to laneForPattern(pattern, hand, timeSec, block)
                }
                val lane = if (laneStreak[hand] >= 2 && lastLaneHand[hand] == picked && tapOrFlick && pattern != "jack") {
                    val alternatives = pool(hand).filter { it != picked }
                    if (alternatives.isEmpty()) picked else rng.choice(alternatives)
                } else picked
                place(type, hand, lane)
                if (!diff.disableDrag) {
                    if (type == FLICK && downbeat(p) && onsetAt(timeSec) > 0.5) {
                        if (rng.nextDouble() < 0.5) notes += makeNote(p, DRAG, lane)
                    } else if (type == TAP && downbeat(p) && onsetAt(timeSec) > 0.45) {
                        if (rng.nextDouble() < 0.28) notes += makeNote(p, DRAG, lane)
                    }
                }
            }
        }

        private fun enforcePlacement(notes: List<Note>) {
            fun conflictFallback() = if (diff.disableDrag) TAP else DRAG
            val groups = notes.groupBy { it.start to it.lane }
            for (group in groups.values) {
                val nonDrag = group.filter { it.type != DRAG }
                for (note in nonDrag.drop(1)) note.type = conflictFallback()
            }
            val holds = notes.filter { it.type == HOLD }.groupBy { it.lane }
            for (note in notes) {
                if ((note.type == TAP || note.type == FLICK) && holds[note.lane].orEmpty().any { it.start < note.start && note.start < it.end }) {
                    note.type = conflictFallback()
                }
            }
            val gapSec = diff.flickGapMs / 1000.0
            var lastFlickTime: Double? = null
            for (note in notes.sortedWith(compareBy<Note> { it.start }.thenBy { it.lane })) {
                val timeSec = timeAtTick(note.start)
                val last = lastFlickTime
                if (last != null) {
                    if (timeSec - last < gapSec && (note.type == TAP || note.type == HOLD)) note.type = conflictFallback()
                    else if (timeSec - last >= gapSec) lastFlickTime = null
                }
                if (note.type == FLICK) lastFlickTime = timeSec
            }
            if (diff.disableDrag) notes.filter { it.type == DRAG }.forEach { it.type = TAP }
            if (diff.disableHold) notes.filter { it.type == HOLD }.forEach { it.type = TAP }
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        private fun fraction(tick: Int): List<Int> {
            val whole = Math.floorDiv(tick, ticksPerBeat)
            val rem = Math.floorMod(tick, ticksPerBeat)
            if (rem == 0) return listOf(whole, 0, 1)
            val divisor = gcd(rem, ticksPerBeat)
            return listOf(whole, rem / divisor, ticksPerBeat / divisor)
        }

        private fun Note.toJson(): JsonObject = buildJsonObject {
            put("above", 1)
            put("alpha", AutoChartConfig.DEFAULT_ALPHA)
            put("endTime", JsonArray(fraction(end).map(::JsonPrimitive)))
            put("isFake", 0)
            put("positionX", lane.toDouble())
            put("size", AutoChartConfig.DEFAULT_SIZE)
            put("speed", 1.0)
            put("startTime", JsonArray(fraction(start).map(::JsonPrimitive)))
            put("type", type)
            put("visibleTime", 999999.0)
            put("yOffset", 0.0)
        }

        fun generate(): List<JsonObject> {
            if (sections.isEmpty() || points.isEmpty()) return emptyList()
            val segments = planSegments(densityFill(selectFlat()))
            val notes = mutableListOf<Note>()
            for (segment in segments) {
                rng.nextInt(left.size)
                rng.nextInt(right.size)
                rng.choice(directions)
                rng.choice(directions)
                emit(segment, notes)
            }
            val ordered = notes.sortedWith(compareBy<Note> { it.start }.thenBy { it.lane }.thenBy { it.type })
            enforcePlacement(ordered)
            return ordered.map { it.toJson() }
        }
    }
}
