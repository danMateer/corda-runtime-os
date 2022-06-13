package net.corda.crypto.impl.decorators

import net.corda.v5.cipher.suite.CryptoService
import net.corda.v5.cipher.suite.CryptoServiceExtensions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoServiceExtensionsUtilsTests {
    @Test
    fun `requiresWrappingKey should return true if extensions contain REQUIRE_WRAPPING_KEY`() {
        val service = mock<CryptoService> {
            on { extensions } doReturn listOf(
                CryptoServiceExtensions.DELETE_KEYS,
                CryptoServiceExtensions.REQUIRE_WRAPPING_KEY
            )
        }
        assertTrue(service.requiresWrappingKey)
    }

    @Test
    fun `requiresWrappingKey should return false if extensions do not contain REQUIRE_WRAPPING_KEY`() {
        val service = mock<CryptoService> {
            on { extensions } doReturn listOf(CryptoServiceExtensions.DELETE_KEYS)
        }
        assertFalse(service.requiresWrappingKey)
    }

    @Test
    fun `supportsKeyDelete should return true if extensions contain REQUIRE_WRAPPING_KEY`() {
        val service = mock<CryptoService> {
            on { extensions } doReturn listOf(
                CryptoServiceExtensions.DELETE_KEYS,
                CryptoServiceExtensions.REQUIRE_WRAPPING_KEY
            )
        }
        assertTrue(service.supportsKeyDelete)
    }

    @Test
    fun `supportsKeyDelete should return false if extensions do not contain REQUIRE_WRAPPING_KEY`() {
        val service = mock<CryptoService> {
            on { extensions } doReturn listOf(CryptoServiceExtensions.REQUIRE_WRAPPING_KEY)
        }
        assertFalse(service.supportsKeyDelete)
    }
}