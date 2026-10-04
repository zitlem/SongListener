package songlistener.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT for one fixed power-of-two size, with precomputed twiddles. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val bitReversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Transforms [re] + i·[im] in place. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size)
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            for (start in 0 until size step len) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len *= 2
        }
    }

    /** Magnitudes of bins 0..size/2 of a real signal. [signal] is not modified. */
    fun magnitudes(signal: DoubleArray): DoubleArray {
        val re = signal.copyOf()
        val im = DoubleArray(size)
        transform(re, im)
        return DoubleArray(size / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }
}
