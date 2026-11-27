package app.georgiamap

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RouterBinaryValidationTest {
    private fun header(
        nodes: Int, edges: Int, points: Int, names: Int, nameBytes: Int,
        cell: Int, rows: Int, cols: Int, gridEntries: Int
    ): ByteBuffer {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("GRT1".toByteArray(Charsets.US_ASCII))
            putInt(nodes)
            putInt(edges)
            putInt(points)
            putInt(names)
            putInt(nameBytes)
            putInt(0) // grid latitude origin
            putInt(0) // grid longitude origin
            putInt(cell)
            putInt(rows)
            putInt(cols)
            putInt(gridEntries)
            flip()
        }
    }

    @Test
    fun rejectsCountsThatWouldAllocateBeyondTheFile() {
        val malformed = header(Int.MAX_VALUE, 1, 2, 1, 0, 10_000, 1, 1, 1)
        try {
            RoadGraph(malformed)
            throw AssertionError("malformed graph header must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun rejectsImpossibleDimensionsAndTruncatedPayloads() {
        val zeroCell = header(2, 1, 2, 1, 0, 0, 1, 1, 1)
        try {
            RoadGraph(zeroCell)
            throw AssertionError("zero-sized grid cell must be rejected")
        } catch (_: IllegalArgumentException) { }

        val truncated = header(2, 1, 2, 1, 0, 10_000, 1, 1, 1)
        try {
            RoadGraph(truncated)
            throw AssertionError("missing graph arrays must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
