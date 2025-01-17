package net.corda.crypto.service.impl

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.cache.caffeine.CacheFactoryImpl
import net.corda.crypto.cipher.suite.CRYPTO_CATEGORY
import net.corda.crypto.cipher.suite.CRYPTO_TENANT_ID
import net.corda.crypto.cipher.suite.CipherSchemeMetadata
import net.corda.crypto.cipher.suite.KeyGenerationSpec
import net.corda.crypto.cipher.suite.KeyMaterialSpec
import net.corda.crypto.cipher.suite.PlatformDigestService
import net.corda.crypto.cipher.suite.SharedSecretWrappedSpec
import net.corda.crypto.cipher.suite.SigningWrappedSpec
import net.corda.crypto.cipher.suite.publicKeyId
import net.corda.crypto.cipher.suite.schemes.KeyScheme
import net.corda.crypto.config.impl.CryptoSigningServiceConfig
import net.corda.crypto.core.DigitalSignatureWithKey
import net.corda.crypto.core.KeyAlreadyExistsException
import net.corda.crypto.core.ShortHash
import net.corda.crypto.core.fullIdHash
import net.corda.crypto.persistence.SigningKeyInfo
import net.corda.crypto.persistence.SigningKeyOrderBy
import net.corda.crypto.service.CryptoServiceFactory
import net.corda.crypto.service.KeyOrderBy
import net.corda.crypto.service.SigningService
import net.corda.crypto.softhsm.SigningRepositoryFactory
import net.corda.metrics.CordaMetrics
import net.corda.utilities.debug
import net.corda.v5.crypto.CompositeKey
import net.corda.v5.crypto.SecureHash
import net.corda.v5.crypto.SignatureSpec
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.util.concurrent.TimeUnit

data class CacheKey(val tenantId: String, val publicKeyId: ShortHash)

/**
 * 1. Provide (mostly) cached versions of the signing key operations, unlike the signing key operations
 * in [SigningRepositoryImpl] which are uncached.
 *
 * 2. Provide wrapping key operations, routing to the appropriate crypto service implementation. The crypto
 *    service wrapping key operations are cached. TODO - remove these and have callers use CryptoServiceFactory?
 *
 *    TODO - rename to SigningHandlerImpl? it's not really a service any more now that crypto processor creates this,
 *    since the crypto processor is a service.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class SigningServiceImpl(
    private val cryptoServiceFactory: CryptoServiceFactory,
    private val signingRepositoryFactory: SigningRepositoryFactory,
    override val schemeMetadata: CipherSchemeMetadata,
    private val digestService: PlatformDigestService,
    private val cache: Cache<CacheKey, SigningKeyInfo>,
) : SigningService {

    /**
     * Secondary constructor used for production purposes, so that the caller does not need to set the
     * cache up themselves but can instead just supply the config.
     */
    @Suppress("LongParameterList")
    constructor(
        cryptoServiceFactory: CryptoServiceFactory,
        signingRepositoryFactory: SigningRepositoryFactory,
        schemeMetadata: CipherSchemeMetadata,
        digestService: PlatformDigestService,
        config: CryptoSigningServiceConfig,
    ) : this(
        cryptoServiceFactory,
        signingRepositoryFactory,
        schemeMetadata,
        digestService,
        CacheFactoryImpl().build(
            "Signing-Key-Cache",
            Caffeine.newBuilder()
                .expireAfterAccess(config.cache.expireAfterAccessMins, TimeUnit.MINUTES)
                .maximumSize(config.cache.maximumSize)
        )
    )

    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
        private const val SIGN_OPERATION_NAME = "sign"
    }

    data class OwnedKeyRecord(val publicKey: PublicKey, val data: SigningKeyInfo)

    override fun getSupportedSchemes(tenantId: String, category: String): List<String> {
        logger.debug { "getSupportedSchemes(tenant=$tenantId, category=$category)" }
        val ref = cryptoServiceFactory.findInstance(tenantId = tenantId, category = category)
        return ref.instance.supportedSchemes.map { it.key.codeName }
    }

    override fun querySigningKeys(
        tenantId: String,
        skip: Int,
        take: Int,
        orderBy: KeyOrderBy,
        filter: Map<String, String>,
    ): Collection<SigningKeyInfo> {
        logger.debug {
            "lookup(tenantId=$tenantId, skip=$skip, take=$take, orderBy=$orderBy, filter=[${filter.keys.joinToString()}]"
        }
        // It isn't easy to use the cache here, since the cache is keyed only by public key and we are
        // querying
        return signingRepositoryFactory.getInstance(tenantId).use {
            it.query(
                skip,
                take,
                orderBy.toSigningKeyOrderBy(),
                filter
            )
        }
    }

    override fun lookupSigningKeysByPublicKeyShortHash(
        tenantId: String,
        keyIds: List<ShortHash>,
    ): Collection<SigningKeyInfo> {
        // Since we are looking up by short IDs there's nothing we can do to handle clashes
        // on short IDs in this case, at this layer. We must therefore rely on the database
        // uniqueness constraints to stop clashes from being created.

        val cachedKeys =
            cache.getAllPresent(keyIds.mapTo(mutableSetOf()) {
                CacheKey(tenantId, it)
            }).mapTo(mutableSetOf()) { it.value }
        val notFound: List<ShortHash> = keyIds - cachedKeys.map { it.id }.toSet()
        if (notFound.isEmpty()) return cachedKeys
        val fetchedKeys = signingRepositoryFactory.getInstance(tenantId).use {
            it.lookupByPublicKeyShortHashes(notFound.toMutableSet())
        }
        fetchedKeys.forEach { cache.put(CacheKey(tenantId, it.id), it) }

        return cachedKeys + fetchedKeys
    }

    override fun lookupSigningKeysByPublicKeyHashes(
        tenantId: String,
        fullKeyIds: List<SecureHash>,
    ): Collection<SigningKeyInfo> {
        fun filterOutMismatches(found: Collection<SigningKeyInfo>) =
            found.map { foundSigningKeyInfo ->
                if (fullKeyIds.contains(foundSigningKeyInfo.fullId)) {
                    foundSigningKeyInfo
                } else {
                    null
                }
            }.filterNotNull()

        val keyIds = fullKeyIds.map { ShortHash.of(it) }
        val cachedMap = cache.getAllPresent(keyIds.mapTo(mutableSetOf()) { CacheKey(tenantId, it) })
        val cachedKeys = cachedMap.map { it.value }
        val cachedMatchingKeys = filterOutMismatches(cachedKeys)
        if (cachedMatchingKeys.size == fullKeyIds.size) return cachedMatchingKeys
        val notFound = fullKeyIds.filter { hash -> cachedMatchingKeys.count { it.fullId == hash } == 0 }
        // if fullKeyIds has duplicates it's possible that notFound is empty here, even though
        // we didn't get as many records from the cache as the number of keys we expected
        if (notFound.isEmpty()) return cachedMatchingKeys

        val fetchedKeys = signingRepositoryFactory.getInstance(tenantId).use {
            it.lookupByPublicKeyHashes(notFound.toMutableSet())
        }
        val fetchedMatchingKeys = filterOutMismatches(fetchedKeys)
        fetchedMatchingKeys.forEach {
            cache.put(CacheKey(tenantId, it.id), it)
        }
        return cachedMatchingKeys + fetchedMatchingKeys
    }

    // TODO- ditch this method and have callers use crytpoServiceFactory directly?
    // so far all our code has been about signing keys and now we start dealing with wrapping keys.
    override fun createWrappingKey(
        hsmId: String,
        failIfExists: Boolean,
        masterKeyAlias: String,
        context: Map<String, String>,
    ) {
        logger.debug {
            "createWrappingKey(hsmId=$hsmId,masterKeyAlias=$masterKeyAlias,failIfExists=$failIfExists," +
                    "onBehalf=${context[CRYPTO_TENANT_ID]})"
        }
        cryptoServiceFactory.getInstance(hsmId).createWrappingKey(masterKeyAlias, failIfExists, context)
    }

    override fun generateKeyPair(
        tenantId: String,
        category: String,
        alias: String,
        scheme: KeyScheme,
        context: Map<String, String>,
    ): PublicKey =
        doGenerateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = alias,
            externalId = null,
            scheme = scheme,
            context = context
        )

    override fun generateKeyPair(
        tenantId: String,
        category: String,
        alias: String,
        externalId: String,
        scheme: KeyScheme,
        context: Map<String, String>,
    ): PublicKey =
        doGenerateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = alias,
            externalId = externalId,
            scheme = scheme,
            context = context
        )

    override fun freshKey(
        tenantId: String,
        category: String,
        scheme: KeyScheme,
        context: Map<String, String>,
    ): PublicKey =
        doGenerateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = null,
            externalId = null,
            scheme = scheme,
            context = context
        )

    override fun freshKey(
        tenantId: String,
        category: String,
        externalId: String,
        scheme: KeyScheme,
        context: Map<String, String>,
    ): PublicKey =
        doGenerateKeyPair(
            tenantId = tenantId,
            category = category,
            alias = null,
            externalId = externalId,
            scheme = scheme,
            context = context
        )

    override fun sign(
        tenantId: String,
        publicKey: PublicKey,
        signatureSpec: SignatureSpec,
        data: ByteArray,
        context: Map<String, String>
    ): DigitalSignatureWithKey {
        val record =
            CordaMetrics.Metric.Crypto.GetOwnedKeyRecordTimer.builder()
                .withTag(CordaMetrics.Tag.OperationName, SIGN_OPERATION_NAME)
                .withTag(CordaMetrics.Tag.PublicKeyType, publicKey::class.java.simpleName)
                .build()
                .recordCallable {
                    getOwnedKeyRecord(tenantId, publicKey)
                }!!

        logger.debug { "sign(tenant=$tenantId, publicKey=${record.data.id})" }
        val scheme = schemeMetadata.findKeyScheme(record.data.schemeCodeName)
        val cryptoService = cryptoServiceFactory.getInstance(record.data.hsmId)
        val spec = SigningWrappedSpec(getKeySpec(record, publicKey, tenantId), record.publicKey, scheme, signatureSpec)
        val signedBytes = cryptoService.sign(spec, data, context + mapOf(CRYPTO_TENANT_ID to tenantId))
        return DigitalSignatureWithKey(record.publicKey, signedBytes)
    }

    override fun deriveSharedSecret(
        tenantId: String,
        publicKey: PublicKey,
        otherPublicKey: PublicKey,
        context: Map<String, String>,
    ): ByteArray {
        val record = getOwnedKeyRecord(tenantId, publicKey)

        logger.info(
            "deriveSharedSecret(tenant={}, publicKey={}, otherPublicKey={})",
            tenantId,
            record.data.id,
            otherPublicKey.publicKeyId()
        )
        val scheme = schemeMetadata.findKeyScheme(record.data.schemeCodeName)
        val cryptoService = cryptoServiceFactory.getInstance(record.data.hsmId)
        val spec =
            SharedSecretWrappedSpec(getKeySpec(record, publicKey, tenantId), record.publicKey, scheme, otherPublicKey)
        return cryptoService.deriveSharedSecret(spec, context + mapOf(CRYPTO_TENANT_ID to tenantId))
    }

    private fun getHsmAlias(
        record: OwnedKeyRecord,
        publicKey: PublicKey,
        tenantId: String,
    ): String = record.data.hsmAlias ?: throw IllegalStateException(
        "HSM alias must be specified if key material is not specified, and both are null for ${publicKey.publicKeyId()} of tenant $tenantId"
    )

    @Suppress("LongParameterList")
    private fun doGenerateKeyPair(
        tenantId: String,
        category: String,
        alias: String?,
        externalId: String?,
        scheme: KeyScheme,
        context: Map<String, String>,
    ): PublicKey {
        logger.info("generateKeyPair(tenant={}, category={}, alias={}))", tenantId, category, alias)
        val ref = cryptoServiceFactory.findInstance(tenantId = tenantId, category = category)
        signingRepositoryFactory.getInstance(tenantId).use { repo ->
            if (alias != null && repo.findKey(alias) != null) {
                throw KeyAlreadyExistsException(
                    "The key with alias $alias already exists for tenant $tenantId",
                    alias,
                    tenantId
                )
            }
            logger.trace(
                "generateKeyPair for tenant={}, category={}, alias={} using wrapping key ${ref.masterKeyAlias}",
                tenantId,
                category,
                alias
            )

            val generatedKey = ref.instance.generateKeyPair(
                KeyGenerationSpec(scheme, alias, ref.masterKeyAlias),
                context + mapOf(
                    CRYPTO_TENANT_ID to tenantId,
                    CRYPTO_CATEGORY to category
                )
            )
            val saveContext = ref.toSaveKeyContext(generatedKey, alias, scheme, externalId)
            val signingKeyInfo = repo.savePrivateKey(saveContext)
            cache.put(CacheKey(tenantId, signingKeyInfo.id), signingKeyInfo)
            return schemeMetadata.toSupportedPublicKey(generatedKey.publicKey)
        }
    }

    @Suppress("ThrowsCount")
    private fun getOwnedKeyRecord(tenantId: String, publicKey: PublicKey): OwnedKeyRecord {
        if (publicKey is CompositeKey) {
            val found = lookupSigningKeysByPublicKeyHashes(tenantId, publicKey.leafKeys.map { it.fullIdHash() })
            val hit = found.firstOrNull()
            if (hit != null) return OwnedKeyRecord(publicKey.leafKeys.filter { it.fullIdHash() == hit.fullId }
                .first(), hit)
            throw IllegalArgumentException(
                "The tenant $tenantId doesn't own any public key in '${publicKey.publicKeyId()}' composite key."
            )
        }
        // now we are not dealing with composite keys

        // Unfortunately this fullIdHash call is an extension function, which is hard to mock, so testing
        // the happy path on this function is hard.
        val requestedFullKeyId = publicKey.fullIdHash(schemeMetadata, digestService)
        val keyId = ShortHash.of(requestedFullKeyId)
        val cacheKey = CacheKey(tenantId, keyId)
        val signingKeyInfo = cache.getIfPresent(cacheKey) ?: run {
            val repo = signingRepositoryFactory.getInstance(tenantId)
            val result = repo.findKey(publicKey)
            if (result == null) throw IllegalArgumentException("The public key '${publicKey.publicKeyId()}' was not found")
            cache.put(cacheKey, result)
            result
        }
        if (signingKeyInfo.fullId != requestedFullKeyId) throw IllegalArgumentException(
            "The tenant $tenantId doesn't own public key '${publicKey.publicKeyId()}'."
        )
        return OwnedKeyRecord(publicKey, signingKeyInfo)
    }

    @Suppress("ThrowsCount")
    private fun getKeySpec(
        record: OwnedKeyRecord,
        publicKey: PublicKey,
        tenantId: String,
    ): KeyMaterialSpec {
        val keyMaterial: ByteArray = record.data.keyMaterial
        val masterKeyAlias = record.data.wrappingKeyAlias 
        val encodingVersion = record.data.encodingVersion ?: throw IllegalStateException(
            "The encoding version for public key ${publicKey.publicKeyId()} of tenant $tenantId must be specified, but is null"
        )
        return KeyMaterialSpec(
            keyMaterial = keyMaterial,
            wrappingKeyAlias = masterKeyAlias,
            encodingVersion = encodingVersion
        )
    }
}

private fun KeyOrderBy.toSigningKeyOrderBy(): SigningKeyOrderBy =
    SigningKeyOrderBy.valueOf(name)
