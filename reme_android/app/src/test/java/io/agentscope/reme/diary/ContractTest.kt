package io.agentscope.reme.diary

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test
    fun acceptsOriginsAndRejectsCredentialLeaks() {
        assertEquals(
            "https://diary.example.com:2333/",
            ServerAddress.parse("https://diary.example.com", "2333").toString(),
        )
        assertEquals(
            "http://[::1]:2333/",
            ServerAddress.parse("http://[::1]:2333", "2333").toString(),
        )
        for ((url, port) in
            listOf(
                "https://name:secret@example.com" to "443",
                "https://example.com/path" to "443",
                "https://example.com?token=secret" to "443",
                "https://example.com/#fragment" to "443",
                "file:///tmp" to "2333",
                "http://example.com:2333" to "2334",
                "http://example.com" to "0",
            )) {
            assertTrue("rejected $url", runCatching { ServerAddress.parse(url, port) }.isFailure)
        }
    }

    @Test
    fun localNetworkPermissionOnlyAppliesToLocalOrigins() {
        for (host in
            listOf(
                "127.0.0.1",
                "10.0.2.2",
                "192.168.1.9",
                "172.16.4.8",
                "::1",
                "fd00::1",
                "diary.local",
            )) assertTrue(ServerAddress.isLocal(host))
        for (host in
            listOf("diary.example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1")) assertFalse(
            ServerAddress.isLocal(host)
        )
    }

    @Test
    fun wavMatchesRealServerPcmContractAndRecoversInterruptedFile() {
        val file = File.createTempFile("diary-recording", ".wav")
        try {
            file.writeBytes(Wav.header(0) + ByteArray(32000))
            assertTrue(Wav.repair(file))
            val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(file.readBytes().copyOfRange(0, 4)))
            assertEquals(32036, data.getInt(4))
            assertEquals(1, data.getShort(22).toInt())
            assertEquals(16000, data.getInt(24))
            assertEquals(16, data.getShort(34).toInt())
            assertEquals(32000, data.getInt(40))
        } finally {
            file.delete()
        }
    }
}
