package net.corda.testutils.internal

import net.corda.testutils.SimVirtualNode
import net.corda.testutils.HoldingIdentity
import net.corda.testutils.tools.RPCRequestDataWrapper
import net.corda.v5.application.persistence.PersistenceService
import net.corda.v5.base.types.MemberX500Name

class SimVirtualNodeBase(
    override val holdingIdentity: HoldingIdentity,
    private val fiber: SimFiber,
    private val injector: FlowServicesInjector,
    private val flowFactory: FlowFactory
) : SimVirtualNode {

    override val member : MemberX500Name = holdingIdentity.member


    /**
     * Calls the flow with the given request. Note that this call happens on the calling thread, which will wait until
     * the flow has completed before returning the response.
     *
     * @input the data to input to the flow
     *
     * @return the response from the flow
     */
    override fun callFlow(input: RPCRequestDataWrapper): String {
        val flowClassName = input.flowClassName
        val flow = flowFactory.createInitiatingFlow(member, flowClassName)
        injector.injectServices(flow, member, fiber, flowFactory)
        return flow.call(input.toRPCRequestData())
    }

    override fun getPersistenceService(): PersistenceService =
        fiber.getOrCreatePersistenceService(member)
}