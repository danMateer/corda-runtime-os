package net.corda.crypto.service.impl

import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.crypto.cipher.suite.CRYPTO_TENANT_ID
import net.corda.crypto.component.impl.AbstractConfigurableComponent
import net.corda.crypto.component.impl.DependenciesTracker
import net.corda.crypto.config.impl.MasterKeyPolicy
import net.corda.crypto.core.CryptoConsts
import net.corda.crypto.core.CryptoConsts.SOFT_HSM_ID
import net.corda.crypto.core.InvalidParamsException
import net.corda.crypto.persistence.HSMStore
import net.corda.crypto.service.CryptoServiceFactory
import net.corda.crypto.service.HSMService
import net.corda.data.crypto.wire.hsm.HSMAssociationInfo
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.schema.configuration.ConfigKeys.CRYPTO_CONFIG
import net.corda.utilities.debug
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger

@Component(service = [HSMService::class])
class HSMServiceImpl @Activate constructor(
    @Reference(service = LifecycleCoordinatorFactory::class)
    coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = ConfigurationReadService::class)
    configurationReadService: ConfigurationReadService,
    @Reference(service = HSMStore::class)
    private val store: HSMStore,
    @Reference(service = CryptoServiceFactory::class)
    private val cryptoServiceFactory: CryptoServiceFactory,
) : AbstractConfigurableComponent<HSMServiceImpl.Impl>(
    coordinatorFactory = coordinatorFactory,
    myName = LifecycleCoordinatorName.forComponent<HSMService>(),
    configurationReadService = configurationReadService,
    upstream = DependenciesTracker.Default(
        setOf(
            LifecycleCoordinatorName.forComponent<HSMStore>(),
            LifecycleCoordinatorName.forComponent<ConfigurationReadService>(),
        )
    ),
    configKeys = setOf(CRYPTO_CONFIG)
), HSMService {
    override fun createActiveImpl(event: ConfigChangedEvent): Impl =
        Impl(logger, store, cryptoServiceFactory)

    override fun assignSoftHSM(tenantId: String, category: String): HSMAssociationInfo =
        impl.assignSoftHSM(tenantId, category)

    override fun findAssignedHSM(tenantId: String, category: String): HSMAssociationInfo? =
        impl.findAssignedHSM(tenantId, category)

    class Impl(
        private val logger: Logger,
        private val store: HSMStore,
        private val cryptoServiceFactory: CryptoServiceFactory,
    ) : DownstreamAlwaysUpAbstractImpl() {
        companion object {
            private fun Map<String, String>.isPreferredPrivateKeyPolicy(policy: String): Boolean =
                this[CryptoConsts.HSMContext.PREFERRED_PRIVATE_KEY_POLICY_KEY] == policy
        }

        fun assignSoftHSM(tenantId: String, category: String): HSMAssociationInfo {
            logger.info("assignSoftHSM(tenant={}, category={})", tenantId, category)
            val existing = store.findTenantAssociation(tenantId, category)
            if(existing != null) {
                logger.warn(
                    "The ${existing.hsmId} HSM already assigned for tenant={}, category={}",
                    tenantId,
                    category)
                ensureWrappingKey(existing)
                return existing
            }
            val association = store.associate(
                tenantId = tenantId,
                category = category,
                hsmId = SOFT_HSM_ID,
                // Defaulting the below to what it used be in crypto default config - but it probably needs be removed now
                masterKeyPolicy = MasterKeyPolicy.UNIQUE
            )
            ensureWrappingKey(association)
            return association
        }

        fun findAssignedHSM(tenantId: String, category: String): HSMAssociationInfo? {
            logger.debug { "findAssignedHSM(tenant=$tenantId, category=$category)"  }
            return store.findTenantAssociation(tenantId, category)
        }

        private fun ensureWrappingKey(association: HSMAssociationInfo) {
            require(!association.masterKeyAlias.isNullOrBlank()) {
                "The master key alias is not specified."
            }

            val cryptoService = cryptoServiceFactory.getInstance(association.hsmId)
            cryptoService.createWrappingKey(
                failIfExists = false,
                wrappingKeyAlias = association.masterKeyAlias
                    ?: throw InvalidParamsException("no masterKeyAlias in association"),
                context = mapOf(
                    CRYPTO_TENANT_ID to association.tenantId
                )
            )
        }
    }
}