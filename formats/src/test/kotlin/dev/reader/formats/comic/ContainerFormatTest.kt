package dev.reader.formats.comic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContainerFormatTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `PK signature is a zip`() =
        assertThat(detectContainer(bytes(0x50, 0x4B, 0x03, 0x04))).isEqualTo(ContainerFormat.ZIP)

    @Test fun `RAR4 signature is rar`() =
        assertThat(detectContainer(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)))
            .isEqualTo(ContainerFormat.RAR)

    @Test fun `RAR5 signature is rar`() =
        assertThat(detectContainer(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)))
            .isEqualTo(ContainerFormat.RAR)

    @Test fun `foreign bytes are unknown`() =
        assertThat(detectContainer(bytes(0x25, 0x50, 0x44, 0x46))).isEqualTo(ContainerFormat.UNKNOWN)

    @Test fun `a too-short buffer is unknown`() =
        assertThat(detectContainer(bytes(0x50, 0x4B))).isEqualTo(ContainerFormat.UNKNOWN)

    @Test fun `an empty buffer is unknown`() =
        assertThat(detectContainer(ByteArray(0))).isEqualTo(ContainerFormat.UNKNOWN)
}
