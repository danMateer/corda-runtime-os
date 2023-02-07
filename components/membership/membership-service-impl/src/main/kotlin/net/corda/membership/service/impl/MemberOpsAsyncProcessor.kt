package net.corda.membership.service.impl

import net.corda.data.membership.async.request.MembershipAsyncRequest
import net.corda.data.membership.async.request.RegistrationAsyncRequest
import net.corda.data.membership.common.RegistrationStatus
import net.corda.membership.lib.toMap
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.registration.InvalidMembershipRegistrationException
import net.corda.membership.registration.NotReadyMembershipRegistrationException
import net.corda.membership.registration.RegistrationProxy
import net.corda.messaging.api.processor.DurableProcessor
import net.corda.messaging.api.records.Record
import net.corda.virtualnode.ShortHash
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.util.UUID

internal class MemberOpsAsyncProcessor(
    private val registrationProxy: RegistrationProxy,
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
    private val membershipPersistenceClient: MembershipPersistenceClient,
) : DurableProcessor<String, MembershipAsyncRequest> {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }
    override fun onNext(events: List<Record<String, MembershipAsyncRequest>>): List<Record<*, *>> {
        return events.mapNotNull {
            it.value?.request
        }
            .flatMap { data ->
                handle(data)
            }
    }

    override val keyClass = String::class.java
    override val valueClass = MembershipAsyncRequest::class.java

    private fun handle(request: Any): Collection<Record<*, *>> {
        return when (request) {
            is RegistrationAsyncRequest -> {
                register(request)
                emptyList()
            }
            else -> {
                logger.warn("Can not handle: $request")
                emptyList()
            }
        }
    }

    private fun register(request: RegistrationAsyncRequest) {
        val holdingIdentityShortHash = ShortHash.of(request.holdingIdentityId)
        val holdingIdentity =
            virtualNodeInfoReadService.getByHoldingIdentityShortHash(holdingIdentityShortHash)?.holdingIdentity
                ?: throw NotReadyMembershipRegistrationException(
                    "Could not find holding identity associated with ${request.holdingIdentityId}"
                )
        val registrationId = try {
            UUID.fromString(request.requestId)
        } catch (e: IllegalArgumentException) {
            logger.warn("Registration ${request.requestId} failed., Invalid request ID.", e)
            return
        }
        try {
            registrationProxy.register(registrationId, holdingIdentity, request.context.toMap())
        } catch (e: InvalidMembershipRegistrationException) {
            membershipPersistenceClient.setRegistrationRequestStatus(
                holdingIdentity,
                registrationId.toString(),
                RegistrationStatus.INVALID,
            )
            logger.warn("Registration ${request.requestId} failed.", e)
        } catch (e: Exception) {
            throw e
        }
    }
}