package com.touchbridge.rpe.domain.engine

import java.math.BigDecimal
import java.math.BigInteger

/**
 * 等价于 Python `Fraction(float(beat)).limit_denominator(1000)`。
 *
 * Python 的 Fraction(float) 是二进制精确值，因此这里用 BigDecimal 精确展开
 * double，再用连分数展开求分母不超过 maxDenominator 的最接近分数。
 */
object BeatFraction {

    data class Fraction(val numerator: BigInteger, val denominator: BigInteger) {
        operator fun compareTo(other: Fraction): Int =
            numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))

        fun minus(other: Fraction): Fraction = Fraction(
            numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
            denominator.multiply(other.denominator),
        )

        fun absApprox(): Double = numerator.toDouble() / denominator.toDouble()

        fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()
    }

    fun of(value: Double): Fraction {
        val bd = BigDecimal(value)
        val scale = bd.scale().coerceAtLeast(0)
        var num = bd.unscaledValue()
        var den = BigInteger.TEN.pow(scale)
        if (den.signum() < 0) {
            num = num.negate()
            den = den.negate()
        }
        // Python 的 Fraction(float) 会约分，这里必须保持同一语义，
        // 否则 1.5 会被表示为 15/10 而得到 [1, 5, 10] 而非 [1, 1, 2]。
        if (den.signum() != 0) {
            val g = num.gcd(den)
            if (g.signum() != 0 && g != BigInteger.ONE) {
                num = num.divide(g)
                den = den.divide(g)
            }
        }
        return Fraction(num, den)
    }

    /** 返回限制分母后的分数（分母 <= maxDenominator，且尽可能接近原值）。 */
    fun limitDenominator(value: Double, maxDenominator: Int = 1000): Fraction {
        val self = of(value)
        if (self.denominator <= BigInteger.valueOf(maxDenominator.toLong())) {
            if (self.denominator.signum() == 0) return Fraction(BigInteger.ZERO, BigInteger.ONE)
            return self
        }

        var p0 = BigInteger.ZERO
        var q0 = BigInteger.ONE
        var p1 = BigInteger.ONE
        var q1 = BigInteger.ZERO
        var n = self.numerator
        var d = self.denominator
        val maxQ = BigInteger.valueOf(maxDenominator.toLong())

        while (true) {
            if (d.signum() == 0) break
            val a = n.divide(d)
            val q2 = q0.add(a.multiply(q1))
            if (q2 > maxQ) break
            val np0 = p1
            val nq0 = q1
            p1 = p0.add(a.multiply(p1))
            q1 = q2
            p0 = np0
            q0 = nq0
            val nn = d
            val dd = n.subtract(a.multiply(d))
            n = nn
            d = dd
        }

        val k = maxQ.subtract(q0).divide(q1)
        val bound1 = Fraction(p0.add(k.multiply(p1)), q0.add(k.multiply(q1)))
        val bound2 = Fraction(p1, q1)

        val diff1 = self.minus(bound1).absApprox().let { kotlin.math.abs(it) }
        val diff2 = self.minus(bound2).absApprox().let { kotlin.math.abs(it) }
        return if (diff2 <= diff1) bound2 else bound1
    }
}
