package net.corda.membership.impl.registration

import net.corda.membership.impl.registration.MemberRole.Companion.extractRolesFromContext
import net.corda.v5.base.types.MemberX500Name
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MemberRoleTest {
    @Test
    fun `no roles return empty collection`() {
        assertThat(extractRolesFromContext(emptyMap()))
            .isEmpty()
    }

    @Test
    fun `notary roles returns Notary`() {
        assertThat(
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "notary",
                    "corda.notary.service.name" to "O=MyNotaryService, L=London, C=GB",
                    "corda.notary.service.plugin" to "net.corda.notary.MyNotaryService",
                )
            )
        )
            .hasSize(1)
            .allMatch {
                it == MemberRole.Notary(
                    MemberX500Name.parse("O=MyNotaryService, L=London, C=GB"),
                    "net.corda.notary.MyNotaryService"
                )
            }
    }

    @Test
    fun `ignores duplicates`() {
        assertThat(
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "notary",
                    "corda.roles.1" to "notary",
                    "corda.roles.2" to "notary",
                    "corda.notary.service.name" to "O=MyNotaryService, L=London, C=GB",
                    "corda.notary.service.plugin" to "net.corda.notary.MyNotaryService",
                )
            )
        )
            .hasSize(1)
    }

    @Test
    fun `throws exception if notary plugin is missing`() {
        assertThrows<IllegalArgumentException> {
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "notary",
                    "corda.notary.service.name" to "O=MyNotaryService, L=London, C=GB",
                )
            )
        }
    }

    @Test
    fun `throws exception if notary service is missing`() {
        assertThrows<IllegalArgumentException> {
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "notary",
                    "corda.notary.service.plugin" to "net.corda.notary.MyNotaryService",
                )
            )
        }
    }

    @Test
    fun `throws exception if notary service is not X500 name`() {
        assertThrows<IllegalArgumentException> {
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "notary",
                    "corda.notary.service.name" to "NOP",
                    "corda.notary.service.plugin" to "net.corda.notary.MyNotaryService",
                )
            )
        }
    }

    @Test
    fun `throws exception if type is unknown`() {
        assertThrows<IllegalArgumentException> {
            extractRolesFromContext(
                mapOf(
                    "corda.roles.0" to "another",
                )
            )
        }
    }

    @Test
    fun `throws exception if we have notary key without notary member`() {
        assertThrows<IllegalArgumentException> {
            extractRolesFromContext(
                mapOf(
                    "corda.notary.service.plugin" to "net.corda.notary.MyNotaryService",
                )
            )
        }
    }
}