package net.corda.membership.impl.httprpc.v1

import net.corda.crypto.client.CryptoOpsClient
import net.corda.data.crypto.wire.CryptoSigningKey
import net.corda.httprpc.exception.ResourceNotFoundException
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleEventHandler
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.membership.httprpc.v1.CertificatesRpcOps.Companion.SIGNATURE_SPEC
import net.corda.v5.cipher.suite.KeyEncodingService
import net.corda.v5.crypto.DigitalSignature
import net.corda.v5.crypto.ECDSA_SECP256R1_CODE_NAME
import net.corda.v5.crypto.SignatureSpec
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class CertificatesRpcOpsImplTest {
    private val cryptoOpsClient = mock<CryptoOpsClient>()
    private val keyEncodingService = mock<KeyEncodingService>()
    private val coordinator = mock<LifecycleCoordinator>()
    private val handler = argumentCaptor<LifecycleEventHandler>()
    private val lifecycleCoordinatorFactory = mock<LifecycleCoordinatorFactory> {
        on { createCoordinator(any(), handler.capture()) } doReturn coordinator
    }

    private val certificatesOps = CertificatesRpcOpsImpl(cryptoOpsClient, keyEncodingService, lifecycleCoordinatorFactory)

    @Nested
    inner class LifeCycleTests {
        @Test
        fun `isRunning returns the coordinator status`() {
            whenever(coordinator.status).doReturn(LifecycleStatus.UP)

            assertThat(certificatesOps.isRunning).isTrue
        }

        @Test
        fun `start starts the coordinator`() {
            certificatesOps.start()

            verify(coordinator).start()
        }

        @Test
        fun `stop stops the coordinator`() {
            certificatesOps.stop()

            verify(coordinator).stop()
        }

        @Test
        fun `UP event will set the status to up`() {
            handler.firstValue.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.UP), mock())

            verify(coordinator).updateStatus(LifecycleStatus.UP, "Dependencies are UP")
        }

        @Test
        fun `DOWN event will set the status to down`() {
            handler.firstValue.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.DOWN), mock())

            verify(coordinator).updateStatus(LifecycleStatus.DOWN, "Dependencies are DOWN")
        }
    }
    @Nested
    inner class GenerateCsrTests {
        private val holdingIdentityId = "id"
        private val keyId = "keyId"
        private val x500Name = "CN=Alice"
        private val role = "TLS"
        private val publicKeyBytes = "123".toByteArray()
        private val key = mock<CryptoSigningKey> {
            on { publicKey } doReturn ByteBuffer.wrap(publicKeyBytes)
            on { schemeCodeName } doReturn ECDSA_SECP256R1_CODE_NAME
            on { tenantId } doReturn holdingIdentityId
        }
        private val publicKey = KeyPairGenerator.getInstance("EC").let { keyPairGenerator ->
            val rnd = mock<SecureRandom> {
                on { nextBytes(any()) } doAnswer {
                    val array = it.arguments[0] as ByteArray
                    array.fill(106)
                }
            }
            keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), rnd)
            keyPairGenerator.generateKeyPair().public
        }

        @BeforeEach
        fun setUp() {
            whenever(cryptoOpsClient.lookup(holdingIdentityId, listOf(keyId))).doReturn(listOf(key))
            whenever(
                cryptoOpsClient.sign(
                    eq(holdingIdentityId),
                    eq(publicKey),
                    eq(SignatureSpec("SHA512withECDSA")),
                    any(),
                    eq(emptyMap())
                )
            ).doReturn(
                DigitalSignature.WithKey(
                    publicKey,
                    byteArrayOf(1),
                    emptyMap()
                )
            )
            whenever(keyEncodingService.decodePublicKey(publicKeyBytes)).doReturn(publicKey)
        }

        @Test
        fun `it throws exception if key is not available`() {
            whenever(cryptoOpsClient.lookup(any(), any())).doReturn(emptyList())

            assertThrows<ResourceNotFoundException> {
                certificatesOps.generateCsr(
                    holdingIdentityId,
                    keyId,
                    x500Name,
                    role,
                    null,
                    null,
                )
            }
        }

        @Test
        fun `it sign the request`() {
            certificatesOps.generateCsr(
                holdingIdentityId,
                keyId,
                x500Name,
                role,
                null,
                null,
            )

            verify(cryptoOpsClient).sign(
                eq(holdingIdentityId),
                eq(publicKey),
                eq(SignatureSpec("SHA512withECDSA")),
                any(),
                eq(emptyMap())
            )
        }

        @Test
        fun `it returns the correct signature`() {
            val pem = certificatesOps.generateCsr(
                holdingIdentityId,
                keyId,
                x500Name,
                role,
                null,
                null,
            )

            assertThat(pem.fromPem().signature).isEqualTo(byteArrayOf(1))
        }

        @Test
        fun `it adds alternative subject names when some are provided`() {
            val pem = certificatesOps.generateCsr(
                holdingIdentityId,
                keyId,
                x500Name,
                role,
                listOf("www.alice.net", "alice.net"),
                null,
            )

            assertThat(
                pem.fromPem()
                    .requestedExtensions
                    .getExtension(Extension.subjectAlternativeName)
            ).isEqualTo(
                Extension(
                    Extension.subjectAlternativeName,
                    true,
                    DEROctetString(
                        GeneralNames(
                            arrayOf(
                                GeneralName(GeneralName.dNSName, "www.alice.net"),
                                GeneralName(GeneralName.dNSName, "alice.net"),
                            )
                        )
                    )
                )
            )
        }

        @Test
        fun `it will not adds alternative subject names when none are provided`() {
            val pem = certificatesOps.generateCsr(
                holdingIdentityId,
                keyId,
                x500Name,
                role,
                null,
                null,
            )

            assertThat(
                pem.fromPem()
                    .requestedExtensions
                    .getExtension(Extension.subjectAlternativeName)
            ).isNull()
        }

        @Test
        fun `it will use the correct x500 name`() {
            val pem = certificatesOps.generateCsr(
                holdingIdentityId,
                keyId,
                x500Name,
                role,
                null,
                emptyMap(),
            )

            assertThat(
                pem.fromPem()
                    .subject
            ).isEqualTo(X500Name(x500Name))
        }

        @Test
        fun `it throws exception if Signature OID can not be inferred`() {
            assertThrows<ResourceNotFoundException> {
                certificatesOps.generateCsr(
                    holdingIdentityId,
                    keyId,
                    x500Name,
                    role,
                    null,
                    mapOf(SIGNATURE_SPEC to "Nop")
                )
            }
        }

        @Test
        fun `it throws exception if key code name is invalid`() {
            whenever(key.schemeCodeName).doReturn("Nop")

            assertThrows<ResourceNotFoundException> {
                certificatesOps.generateCsr(
                    holdingIdentityId,
                    keyId,
                    x500Name,
                    role,
                    null,
                    null,
                )
            }
        }

        private fun String.fromPem(): PKCS10CertificationRequest {
            return PEMParser(this.reader()).use { parser ->
                parser.readObject() as PKCS10CertificationRequest
            }
        }
    }
}