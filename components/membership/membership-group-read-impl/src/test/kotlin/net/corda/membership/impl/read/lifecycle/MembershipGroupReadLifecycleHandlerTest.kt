package net.corda.membership.impl.read.lifecycle

import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.libs.configuration.SmartConfig
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationHandle
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.Resource
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.schema.configuration.ConfigKeys.BOOT_CONFIG
import net.corda.schema.configuration.ConfigKeys.MESSAGING_CONFIG
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class MembershipGroupReadLifecycleHandlerTest {

    lateinit var handler: MembershipGroupReadLifecycleHandler

    val componentRegistrationHandle: RegistrationHandle = mock()
    val configRegistrationHandle: Resource = mock()

    val configurationReadService: ConfigurationReadService = mock {
        on { registerComponentForUpdates(any(), any()) } doReturn configRegistrationHandle
    }

    val coordinator: LifecycleCoordinator = mock {
        on { followStatusChangesByName(any()) } doReturn componentRegistrationHandle
    }

    val activateFunction: (Map<String, SmartConfig>, String) -> Unit = mock()
    val deactivateFunction: (String) -> Unit = mock()

    @BeforeEach
    fun setUp() {
        handler = MembershipGroupReadLifecycleHandler.Impl(
            configurationReadService,
            activateFunction,
            deactivateFunction
        )
    }

    @Test
    fun `Start event`() {
        handler.processEvent(StartEvent(), coordinator)

        verify(coordinator).followStatusChangesByName(
            eq(
                setOf(
                    LifecycleCoordinatorName.forComponent<ConfigurationReadService>()
                )
            )
        )
    }

    @Test
    fun `Dependency handle is closed if it was already created`() {
        handler.processEvent(StartEvent(), coordinator)
        handler.processEvent(StartEvent(), coordinator)

        // by default this asserts for only one call
        verify(componentRegistrationHandle).close()
        verify(coordinator, times(2)).followStatusChangesByName(
            eq(
                setOf(
                    LifecycleCoordinatorName.forComponent<ConfigurationReadService>()
                )
            )
        )
    }

    @Test
    fun `Stop event`() {
        handler.processEvent(StopEvent(), coordinator)

        verify(deactivateFunction).invoke(any())

        //these handles are only set if other lifecycle events happen first. In this case they are null when stopping.
        verify(componentRegistrationHandle, never()).close()
        verify(configRegistrationHandle, never()).close()
    }

    @Test
    fun `Component registration handle is created after starting and closed when stopping`() {
        handler.processEvent(StartEvent(), coordinator)
        handler.processEvent(StopEvent(), coordinator)

        verify(componentRegistrationHandle).close()
    }

    @Test
    fun `Config handle is created after registration status changes to UP and closed when stopping`() {
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.UP), coordinator)
        handler.processEvent(StopEvent(), coordinator)

        verify(configRegistrationHandle).close()
    }

    @Test
    fun `Registration status UP registers for config updates`() {
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.UP), coordinator)

        verify(configurationReadService).registerComponentForUpdates(
            eq(coordinator), eq(setOf(BOOT_CONFIG, MESSAGING_CONFIG))
        )
        verify(coordinator, never()).updateStatus(eq(LifecycleStatus.UP), any())
    }

    @Test
    fun `Registration status DOWN deactivates component`() {
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.DOWN), coordinator)

        verify(deactivateFunction).invoke(any())
    }

    @Test
    fun `Registration status ERROR deactivates component`() {
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.ERROR), coordinator)

        verify(deactivateFunction).invoke(any())
    }

    @Test
    fun `Registration status DOWN closes config handle if status was previously UP`() {
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.UP), coordinator)
        handler.processEvent(RegistrationStatusChangeEvent(mock(), LifecycleStatus.DOWN), coordinator)

        verify(configRegistrationHandle).close()
    }

    @Test
    fun `Config received event while component is running`() {
        val bootConfig: SmartConfig = mock()
        val messagingConfig: SmartConfig = mock()
        val configs = mapOf(
            BOOT_CONFIG to bootConfig,
            MESSAGING_CONFIG to messagingConfig
        )
        handler.processEvent(ConfigChangedEvent(setOf(BOOT_CONFIG, MESSAGING_CONFIG), configs), coordinator)

        verify(activateFunction).invoke(eq(configs), any())
    }
}
