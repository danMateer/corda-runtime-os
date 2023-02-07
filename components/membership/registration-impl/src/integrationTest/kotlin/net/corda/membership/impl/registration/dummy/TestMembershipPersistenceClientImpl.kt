package net.corda.membership.impl.registration.dummy

import net.corda.data.KeyValuePairList
import net.corda.data.membership.common.ApprovalRuleDetails
import net.corda.data.membership.common.RegistrationStatus
import net.corda.data.membership.preauth.PreAuthToken
import net.corda.membership.lib.approval.ApprovalRuleParams
import net.corda.membership.lib.registration.RegistrationRequest
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.persistence.client.MembershipPersistenceResult
import net.corda.v5.base.types.LayeredPropertyMap
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.membership.GroupParameters
import net.corda.v5.membership.MemberInfo
import net.corda.virtualnode.HoldingIdentity
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.propertytypes.ServiceRanking
import java.time.Instant
import java.util.UUID

@ServiceRanking(Int.MAX_VALUE)
@Component(service = [MembershipPersistenceClient::class])
class TestMembershipPersistenceClientImpl @Activate constructor() : MembershipPersistenceClient {
    override fun persistMemberInfo(
        viewOwningIdentity: HoldingIdentity,
        memberInfos: Collection<MemberInfo>,
    ) = MembershipPersistenceResult.success()

    override fun persistGroupPolicy(
        viewOwningIdentity: HoldingIdentity,
        groupPolicy: LayeredPropertyMap,
    ) = MembershipPersistenceResult.Success(1)

    override fun persistGroupParametersInitialSnapshot(
        viewOwningIdentity: HoldingIdentity
    ): MembershipPersistenceResult<KeyValuePairList> = MembershipPersistenceResult.Success(KeyValuePairList())

    override fun persistGroupParameters(
        viewOwningIdentity: HoldingIdentity,
        groupParameters: GroupParameters,
    ): MembershipPersistenceResult<KeyValuePairList> = MembershipPersistenceResult.Success(KeyValuePairList())

    override fun addNotaryToGroupParameters(
        viewOwningIdentity: HoldingIdentity,
        notary: MemberInfo,
    ): MembershipPersistenceResult<KeyValuePairList> = MembershipPersistenceResult.Success(KeyValuePairList())

    override fun persistRegistrationRequest(
        viewOwningIdentity: HoldingIdentity,
        registrationRequest: RegistrationRequest,
    ): MembershipPersistenceResult<Unit> = MembershipPersistenceResult.success()

    override fun setMemberAndRegistrationRequestAsApproved(
        viewOwningIdentity: HoldingIdentity,
        approvedMember: HoldingIdentity,
        registrationRequestId: String,
    ): MembershipPersistenceResult<MemberInfo> = MembershipPersistenceResult.Failure("Unsupported")

    override fun setMemberAndRegistrationRequestAsDeclined(
        viewOwningIdentity: HoldingIdentity,
        declinedMember: HoldingIdentity,
        registrationRequestId: String,
    ): MembershipPersistenceResult<Unit> = MembershipPersistenceResult.success()

    override fun setRegistrationRequestStatus(
        viewOwningIdentity: HoldingIdentity,
        registrationId: String,
        registrationRequestStatus: RegistrationStatus,
    ): MembershipPersistenceResult<Unit> = MembershipPersistenceResult.success()

    override fun mutualTlsAddCertificateToAllowedList(
        mgmHoldingIdentity: HoldingIdentity,
        subject: String,
    ): MembershipPersistenceResult<Unit> = MembershipPersistenceResult.success()

    override fun mutualTlsRemoveCertificateFromAllowedList(
        mgmHoldingIdentity: HoldingIdentity,
        subject: String,
    ): MembershipPersistenceResult<Unit> = MembershipPersistenceResult.success()

    override fun generatePreAuthToken(
        mgmHoldingIdentity: HoldingIdentity,
        preAuthTokenId: UUID,
        ownerX500Name: MemberX500Name,
        ttl: Instant?,
        remarks: String?,
    ) = MembershipPersistenceResult.success()

    override fun revokePreAuthToken(
        mgmHoldingIdentity: HoldingIdentity,
        preAuthTokenId: UUID,
        remarks: String?,
    ): MembershipPersistenceResult<PreAuthToken> = MembershipPersistenceResult.Failure("Unsupported")

    override fun addApprovalRule(
        viewOwningIdentity: HoldingIdentity,
        ruleParams: ApprovalRuleParams,
    ): MembershipPersistenceResult<ApprovalRuleDetails> = MembershipPersistenceResult.Failure("Unsupported")

    override fun deleteApprovalRule(
        viewOwningIdentity: HoldingIdentity,
        ruleId: String,
    ) = MembershipPersistenceResult.success()

    override val isRunning = true

    override fun start() {}

    override fun stop() {}
}