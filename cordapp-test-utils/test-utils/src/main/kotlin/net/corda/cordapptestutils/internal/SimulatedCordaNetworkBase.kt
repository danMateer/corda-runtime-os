package net.corda.cordapptestutils.internal

import net.corda.cordapptestutils.HoldingIdentity
import net.corda.cordapptestutils.SimulatedCordaNetwork
import net.corda.cordapptestutils.SimulatedVirtualNode
import net.corda.cordapptestutils.internal.flows.BaseFlowFactory
import net.corda.cordapptestutils.internal.flows.DefaultServicesInjector
import net.corda.cordapptestutils.internal.flows.FlowFactory
import net.corda.cordapptestutils.internal.flows.FlowServicesInjector
import net.corda.cordapptestutils.internal.messaging.SimFiber
import net.corda.cordapptestutils.internal.messaging.SimFiberBase
import net.corda.cordapptestutils.internal.tools.CordaFlowChecker
import net.corda.cordapptestutils.tools.FlowChecker
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.ResponderFlow
import net.corda.v5.base.types.MemberX500Name

/**
 * Simulator simulated Corda 5 network which will run in-process. It allows a lightweight "virtual node" to be created
 * which can run flows against a given member. Note that the flows in nodes do not have to be symmetrical;
 * an initiating flow class can be registered for one party with a responding flow class registered for another.
 *
 * Simulator uses lightweight versions of Corda services to help mimic the Corda network while ensuring that your
 * flow will work well with the real thing. These can be mocked out or wrapped if required, but most of the time the
 * defaults will be enough.
 *
 * Instances of initiator and responder flows can also be "uploaded"; however, these will not undergo the same checks
 * as a flow class "upload". This should generally only be used for mocked or faked flows to test matching responder or
 * initiator flows in isolation.
 *
 * @flowChecker Checks any flow class. Defaults to checking the various hooks which the real Corda would require
 * @fiber The simulated "fiber" with which responder flows will be registered by protocol
 * @injector An injector to initialize services annotated with @CordaInject in flows and subflows
 */
class SimulatedCordaNetworkBase  (
    private val flowChecker: FlowChecker = CordaFlowChecker(),
    private val fiber: SimFiber = SimFiberBase(),
    private val injector: FlowServicesInjector = DefaultServicesInjector()
) : SimulatedCordaNetwork {

    private val flowFactory: FlowFactory = BaseFlowFactory()

    /**
     * Registers a flow class against a given member with Simulator. Flow classes will be checked
     * for validity. Responder flows will also be registered against their protocols.
     *
     * @member The member for whom this node will be created.
     * @flowClasses The flows which will be available to run in the nodes. Must be `RPCStartableFlow`
     * or `ResponderFlow`.
     */
    override fun createVirtualNode(
        holdingIdentity: HoldingIdentity,
        vararg flowClasses: Class<out Flow>)
    : SimulatedVirtualNode {
        flowClasses.forEach {
            flowChecker.check(it)
            registerAnyResponderWithFiber(holdingIdentity.member, it)
        }
        return SimulatedVirtualNodeBase(holdingIdentity, fiber, injector, flowFactory)
    }

    private fun registerAnyResponderWithFiber(
        member: MemberX500Name,
        flowClass: Class<out Flow>
    ) {
        val protocolIfResponder = flowClass.getAnnotation(InitiatedBy::class.java)?.protocol
        if (protocolIfResponder != null) {
            val responderFlowClass = castInitiatedFlowToResponder(flowClass)
            fiber.registerResponderClass(member, protocolIfResponder, responderFlowClass)
        }
    }

    private fun castInitiatedFlowToResponder(flowClass: Class<out Flow>) : Class<ResponderFlow> {
        if (ResponderFlow::class.java.isAssignableFrom(flowClass)) {
            @Suppress("UNCHECKED_CAST")
            return flowClass as Class<ResponderFlow>
        } else throw IllegalArgumentException(
            "${flowClass.simpleName} has an @${InitiatedBy::class.java} annotation, but " +
                    "it is not a ${ResponderFlow::class.java}"
        )
    }

    /**
     * Creates a virtual node holding a concrete instance of a responder flow. Note that this bypasses all
     * checks for constructor and annotations on the flow.
     */
    override fun createVirtualNode(
        responder: HoldingIdentity,
        protocol: String,
        responderFlow: ResponderFlow)
    : SimulatedVirtualNode {
        fiber.registerResponderInstance(responder.member, protocol, responderFlow)
        return SimulatedVirtualNodeBase(responder, fiber, injector, flowFactory)
    }

    override fun close() {
        fiber.close()
    }
}