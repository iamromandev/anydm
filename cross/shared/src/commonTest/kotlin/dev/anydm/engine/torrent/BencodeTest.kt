package dev.anydm.engine.torrent

import dev.anydm.engine.torrent.BValue.BBytes
import dev.anydm.engine.torrent.BValue.BDict
import dev.anydm.engine.torrent.BValue.BInt
import dev.anydm.engine.torrent.BValue.BList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BencodeTest {
    private fun decode(
        s: String,
        maxDepth: Int = MAX_DEPTH,
    ) = decodeBencode(s.encodeToByteArray(), maxDepth)

    private fun ok(s: String): BValue = assertIs<DecodeResult.Ok>(decode(s), "decoding \"$s\"").value

    private fun err(
        s: String,
        maxDepth: Int = MAX_DEPTH,
    ): String = assertIs<DecodeResult.Err>(decode(s, maxDepth), "decoding \"$s\"").reason

    @Test
    fun decodesIntegers() {
        assertEquals(BInt(42), ok("i42e"))
        assertEquals(BInt(-7), ok("i-7e"))
        assertEquals(BInt(0), ok("i0e"))
    }

    @Test
    fun rejectsMalformedIntegers() {
        listOf(
            "ie",
            "i-e",
            "i-0e",
            "i03e",
            "i-03e",
            "i4",
            "i4x e",
            "i--1e",
            "i+1e",
            "i1.5e",
        ).forEach { assertEquals("Not bencoded", err(it)) }
    }

    @Test
    fun integerLimitsAreGuarded() {
        assertEquals(BInt(Long.MAX_VALUE), ok("i9223372036854775807e"))
        assertEquals(BInt(-Long.MAX_VALUE), ok("i-9223372036854775807e"))
        assertEquals("Not bencoded", err("i9223372036854775808e"))
        assertEquals("Not bencoded", err("i-9223372036854775809e"))
        assertEquals("Not bencoded", err("i99999999999999999999999999e"))
    }

    @Test
    fun decodesStrings() {
        assertEquals(BBytes("spam".encodeToByteArray()), ok("4:spam"))
        assertEquals(BBytes(ByteArray(0)), ok("0:"))
    }

    @Test
    fun rejectsMalformedStrings() {
        listOf("5:abc", "4", "4spam", ":a", "01:a", "-1:a", "x:a").forEach { assertEquals("Not bencoded", err(it)) }
    }

    @Test
    fun hugeDeclaredLengthIsRejectedWithoutAllocating() {
        listOf("999999999:x", "2147483647:x", "2147483648:x", "4294967296:x", "99999999999999999999999:x").forEach {
            assertEquals("Not bencoded", err(it))
        }
    }

    @Test
    fun declaredLengthThatWrapsAroundSixtyFourBitsIsRejected() {
        // 2^64 + 1 wraps to 1 with unchecked arithmetic, which would decode as the one byte "x".
        assertEquals("Not bencoded", err("18446744073709551617:x"))
    }

    @Test
    fun declaredLengthMustFitTheRemainingBytes() {
        assertEquals(BBytes("abc".encodeToByteArray()), ok("3:abc"))
        assertEquals("Not bencoded", err("4:abc"))
        assertEquals("Not bencoded", err("l9:abce"))
    }

    @Test
    fun decodesListsAndDicts() {
        assertEquals(BList(listOf(BInt(1), BBytes("a".encodeToByteArray()), BList(emptyList()))), ok("li1e1:alee"))
        val d = assertIs<BDict>(ok("d3:bar4:spam3:fooi42ee"))
        assertEquals(BInt(42), d.entries["foo"])
        assertEquals(BBytes("spam".encodeToByteArray()), d.entries["bar"])
        assertEquals(BDict(emptyMap()), ok("de"))
    }

    @Test
    fun rejectsMalformedContainers() {
        listOf("l", "li1e", "d", "d3:foo", "d3:fooi1e", "di1ei2ee", "d3:fooi1e3:fooi2ee", "lx", "e", "dxe").forEach {
            assertEquals("Not bencoded", err(it))
        }
    }

    @Test
    fun rejectsTrailingBytesAndEmptyInput() {
        assertEquals("Not bencoded", err("i1ex"))
        assertEquals("Not bencoded", err("i1ei2e"))
        assertEquals("Not bencoded", err(""))
    }

    @Test
    fun depthLimitIsExactlySixtyFour() {
        assertIs<DecodeResult.Ok>(decodeBencode(nestedLists(64)))
        assertEquals("Too deeply nested", assertIs<DecodeResult.Err>(decodeBencode(nestedLists(65))).reason)
        assertIs<DecodeResult.Ok>(decodeBencode(nestedDicts(64)))
        assertEquals("Too deeply nested", assertIs<DecodeResult.Err>(decodeBencode(nestedDicts(65))).reason)
    }

    @Test
    fun customDepthLimitIsHonoured() {
        assertIs<DecodeResult.Ok>(decode("lli1eee", maxDepth = 2))
        assertEquals("Too deeply nested", err("llli1eeee", maxDepth = 2))
    }

    @Test
    fun tenThousandLevelsReturnErrInsteadOfOverflowingTheStack() {
        assertEquals("Too deeply nested", assertIs<DecodeResult.Err>(decodeBencode(nestedLists(10_000))).reason)
        assertEquals("Too deeply nested", assertIs<DecodeResult.Err>(decodeBencode(nestedDicts(10_000))).reason)
        // Unterminated deep input must also fail cleanly.
        assertIs<DecodeResult.Err>(decodeBencode(ByteArray(100_000) { 'l'.code.toByte() }))
    }

    @Test
    fun everyTruncationOfAValidTorrentReturnsErr() {
        val bytes = multiTorrent("t", listOf(fileEntry(3, "a", "b.txt"), fileEntry(4, "c.txt")))
        assertIs<DecodeResult.Ok>(decodeBencode(bytes))
        for (n in 0 until bytes.size) assertIs<DecodeResult.Err>(decodeBencode(bytes.copyOf(n)), "prefix of $n bytes")
    }

    @Test
    fun randomBytesNeverThrow() {
        val rnd = kotlin.random.Random(7)
        val alphabet = "ldie:0123456789-x".encodeToByteArray()
        repeat(2_000) {
            val b = ByteArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.size)] }
            val r = decodeBencode(b)
            assertTrue(r is DecodeResult.Ok || r is DecodeResult.Err)
        }
    }

    @Test
    fun theNodeBudgetCountsEveryValue() {
        // A list with three integers is four values.
        assertIs<DecodeResult.Ok>(decodeBencode("li1ei2ei3ee".encodeToByteArray(), maxNodes = 4))
        assertEquals("Too complex", assertIs<DecodeResult.Err>(decodeBencode("li1ei2ei3ee".encodeToByteArray(), maxNodes = 3)).reason)
    }

    @Test
    fun millionsOfTinyValuesAreTooComplexInsteadOfExhaustingMemory() {
        for (unit in listOf("le", "0:", "de", "i1e")) {
            val bytes = concat(listOf("l".encodeToByteArray(), repeated(unit, 5_000_000), "e".encodeToByteArray()))
            assertEquals("Too complex", assertIs<DecodeResult.Err>(decodeBencode(bytes)).reason, "unit $unit")
        }
    }
}
