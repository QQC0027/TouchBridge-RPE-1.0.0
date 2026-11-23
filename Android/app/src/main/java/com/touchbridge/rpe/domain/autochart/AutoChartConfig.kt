package com.touchbridge.rpe.domain.autochart

data class AutoChartOptions(
    val tier: String = "hard",
    val allTiers: Boolean = false,
    val keyMode: String = "auto",
    val density: Double = 1.0,
    val lineFx: String = "none",
    val pulseStyle: String = "chain",
    val decoWhen: String = "intro_chorus",
    val mainY: Double = -300.0,
    val mixSpec: String = "",
    val mainSwing: Boolean = false,
    val tapWeight: Double? = null,
    val holdWeight: Double? = null,
    val flickWeight: Double? = null,
    val dragWeight: Double? = null,
    val seed: Long = 42L,
    val disableDrag: Boolean = false,
    val sweepFlickOnly: Boolean = false,
)

object AutoChartConfig {
    const val SAMPLE_RATE = 22050
    const val HOP_LENGTH = 512
    const val MAX_AUDIO_DURATION_SEC = 30 * 60
    const val MAX_AUDIO_SAMPLES = SAMPLE_RATE.toLong() * MAX_AUDIO_DURATION_SEC
    const val RPE_VERSION = 160
    const val DEFAULT_SPEED = 10.0
    const val DEFAULT_ALPHA = 255
    const val DEFAULT_SIZE = 1.0
    val BAND_LOW = 20..250
    val BAND_MID = 250..2000
    val BAND_HIGH = 2000..11025
    val LANES_2 = listOf(-225, 225)
    val LANES_4 = listOf(-225, -75, 75, 225)
    val LANES_6 = listOf(-450, -300, -150, 150, 300, 450)
    const val TAP = 1
    const val HOLD = 2
    const val FLICK = 3
    const val DRAG = 4
    const val SUSTAIN_THRESHOLD = 0.08
    const val SUSTAIN_MAX_GAP = 2
    val HOLD_BEATS_TIER = listOf(0.25, 0.375, 0.5, 0.75, 1.0, 1.5, 2.0)
    const val HOLD_MAX_BEATS = 2.0
    val HOLD_SHORT_TIER = listOf(0.25, 0.375, 0.5)
    val HOLD_LONG_TIER = listOf(0.75, 1.0, 1.5, 2.0)
    const val HOLD_COOLDOWN_SEC = 0.4
    const val SECTION_WINDOW = 8
    const val DENSITY_LOW = 0.25
    const val DENSITY_MID = 0.55
    const val DENSITY_HIGH = 0.85
    const val PHRASE_NOTES = 8
    const val BIAS_FLIP_PHRASES = 2
    const val SEGMENT_BEATS = 4
    const val SUSTAIN_RMS_THRESHOLD = 0.25
    const val SUSTAIN_RMS_MIN_FRAMES = 3
    const val LEAD_IN_BEATS = 2

    data class Preset(
        val label: String, val level: Int,
        val keepRatio: Double, val minGapBeats: Double, val lanes: List<Int>,
        val minGapMs: Int, val jackMs: Int, val rollGapMs: Int,
        val tapWeight: Double, val holdWeight: Double, val flickWeight: Double, val dragWeight: Double,
        val burstProb: Double, val subdivision: Int, val fillRatio: Double,
        val targetNpb: Map<String, Double>, val maxNps: Double, val maxChord: Int, val quadProb: Double,
        val alternateMs: Int, val jackMax: Int,
        val patternWeights: Map<String, Map<String, Double>>, val patterns: List<String>,
        val disableDrag: Boolean = false, val disableHold: Boolean = false,
        val flickGapMs: Double = 150.0, val rmsSustain: Double = SUSTAIN_RMS_THRESHOLD,
        val segmentBeats: Int = SEGMENT_BEATS,
        val customMotifs: Map<String, List<Motif>> = emptyMap(),
        val motifWeights: Map<String, Double> = emptyMap(),
    )

    data class Motif(val name: String, val tier: Int, val units: List<String>)

    private fun npb(sparse: Double, mid: Double, dense: Double) = mapOf("sparse" to sparse, "mid" to mid, "dense" to dense)
    private fun weights(spec: String): Map<String, Double> = spec.split(' ').associate { token ->
        token.substringBefore(':') to token.substringAfter(':').toDouble()
    }
    private fun weights(sparse: String, mid: String, dense: String) =
        mapOf("sparse" to weights(sparse), "mid" to weights(mid), "dense" to weights(dense))

    val presets: Map<String, Preset> = linkedMapOf(
        "easy" to Preset("EASY", 5, .30, 1.5, LANES_4, 350, 350, 300, .50, .12, .18, .20,
            .08, 4, .30, npb(.4, .8, 1.2), 3.0, 2, 0.0, 260, 2,
            weights("flow:.40 roll:.20 dragchain:.20 holdleft:.10 holdright:.10",
                "alternate:.30 holdleft:.20 holdright:.20 flow:.20 chord:.10",
                "alternate:.30 chord:.30 flow:.20 mirror:.20"),
            "flow roll holdleft roll holdright dragchain".split(' ')),
        "normal" to Preset("NORMAL", 9, .45, 1.0, LANES_4, 250, 250, 220, .45, .14, .20, .21,
            .15, 8, .50, npb(.6, 1.1, 1.8), 5.0, 2, 0.0, 220, 2,
            weights("flow:.35 roll:.30 dragchain:.20 holdleft:.075 holdright:.075",
                "alternate:.30 stair:.10 chord:.15 stream:.15 flow:.20 holdleft:.05 holdright:.05",
                "alternate:.30 chord:.25 mirror:.15 stair:.15 flow:.15"),
            "alternate roll chord holdleft roll dragchain".split(' ')),
        "hard" to Preset("HARD", 12, .60, .5, LANES_6, 150, 150, 150, .38, .18, .22, .22,
            .30, 8, .70, npb(.8, 1.5, 2.8), 7.0, 3, .02, 170, 3,
            weights("flow:.30 dragchain:.25 roll:.20 holdleft:.125 holdright:.125",
                "alternate:.25 stair:.15 chord:.15 stream:.15 syncop:.10 holdleft:.10 holdright:.10",
                "alternate:.25 stream:.15 jack:.10 stair:.15 chord:.15 mirror:.10 burst:.05 offsetchord:.05"),
            "alternate zigzag chord quad holdleft dragchain stream holdright mirror".split(' ')),
        "expert" to Preset("EXPERT", 15, .72, .25, LANES_6, 120, 120, 130, .35, .20, .22, .23,
            .45, 16, .80, npb(.9, 1.9, 3.9), 9.0, 3, .05, 150, 4,
            weights("flow:.30 dragchain:.20 roll:.20 holdleft:.15 holdright:.15",
                "alternate:.25 stair:.15 chord:.15 stream:.15 syncop:.10 jack:.05 offsetchord:.05 holdleft:.05 holdright:.05",
                "alternate:.22 stream:.15 jack:.12 stair:.15 chord:.12 mirror:.08 burst:.06 offsetchord:.06 triple:.04"),
            "alternate stair chord quad holdleft mirror jack holdright stream tri_alt".split(' ')),
        "master" to Preset("MASTER", 17, .85, 0.0, LANES_6, 80, 80, 110, .32, .20, .24, .24,
            .60, 16, 1.0, npb(1.0, 2.4, 5.4), 13.0, 3, .10, 130, 4,
            weights("flow:.30 dragchain:.20 roll:.20 holdleft:.15 holdright:.15",
                "alternate:.25 stair:.15 chord:.15 stream:.15 syncop:.10 jack:.05 offsetchord:.05 holdleft:.05 holdright:.05",
                "alternate:.20 stream:.15 jack:.13 stair:.15 chord:.12 mirror:.08 burst:.07 offsetchord:.05 triple:.05"),
            "alternate stair jack stream chord mirror offsetchord quad holdleft burst holdright tri_alt 2plus3 cross_alt".split(' ')),
    )

    val patternWeights = mapOf(
        "even" to weights("stair:.25 mirror:.10 alternate:.30 flow:.20 random:.15"),
        "syncopated" to weights("stair:.10 mirror:.15 alternate:.20 flow:.10 burst:.45"),
        "sparse" to weights("stair:.10 mirror:.05 alternate:.10 flow:.60 random:.15"),
    )

    private fun motif(name: String, tier: Int, units: String) = Motif(name, tier, units.split(' '))
    val motifLibrary = mapOf(
        "intro/break" to listOf(
            motif("solo_flow", 1, "flow dragchain flow flow"),
            motif("snake_flow", 1, "dragchain dragchain flow holdright"),
            motif("breath_alt", 1, "flow dragchain flow zigzag"),
        ),
        "verse" to listOf(
            motif("alt_hold", 1, "alternate zigzag alternate flow"),
            motif("stair_drag", 1, "stair_up dragchain stair_down dragchain"),
            motif("zigzag_alt", 1, "zigzag alternate zigzag alternate"),
            motif("syncop_flow", 2, "syncop flow syncop flow"),
            motif("drag_sea", 2, "drag_sea drag_sea dragchain drag_sea"),
            motif("tri_verse", 2, "tri_alt alternate tri_alt flow"),
        ),
        "chorus" to listOf(
            motif("sweep", 1, "stair_up mirror_chord alternate stair_down"),
            motif("alt_burst", 1, "alternate burst alternate stair_up"),
            motif("stream_jack", 1, "stream jack stream mirror_chord"),
            motif("roundtrip", 1, "zigzag zigzag mirror_chord alternate"),
            motif("syncop_chord", 2, "syncop syncop offsetchord burst"),
            motif("alt_offset", 2, "alternate offsetchord stream jack"),
            motif("triple_burst", 2, "burst offsetchord burst triple"),
            motif("drag_fury", 2, "drag_sea drag_sea drag_sea drag_sea"),
            motif("snake_fury", 2, "snake_tap snake_tap offsetchord snake_tap"),
            motif("quad_power", 2, "quad offsetchord quad burst"),
            motif("tri_cluster", 2, "tri_alt 2plus3 tri_alt burst"),
            motif("p23_fury", 2, "2plus3 alternate 2plus3 burst"),
            motif("cross_axis", 2, "cross_alt alternate cross_alt mirror_chord"),
        ),
    )

    fun tiers(options: AutoChartOptions): List<String> = if (options.allTiers) presets.keys.toList() else listOf(options.tier)

    fun preset(tier: String, options: AutoChartOptions): Preset {
        val base = presets.getValue(tier)
        return base.copy(
            lanes = when (options.keyMode) { "2k" -> LANES_2; "4k" -> LANES_4; "6k" -> LANES_6; else -> base.lanes },
            tapWeight = options.tapWeight ?: base.tapWeight,
            holdWeight = options.holdWeight ?: base.holdWeight,
            flickWeight = options.flickWeight ?: base.flickWeight,
            dragWeight = options.dragWeight ?: if (options.disableDrag) 0.0 else base.dragWeight,
            disableDrag = options.disableDrag || options.dragWeight?.let { it <= 0.01 } == true,
            disableHold = options.holdWeight?.let { it <= 0.01 } == true,
        )
    }
}
