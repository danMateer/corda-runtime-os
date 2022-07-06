package net.corda.chunking.db.impl.tests

import com.google.common.jimfs.Jimfs
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import java.util.UUID
import javax.persistence.PersistenceException
import net.corda.chunking.datamodel.ChunkingEntities
import net.corda.chunking.db.impl.persistence.database.DatabaseCpiPersistence
import net.corda.db.admin.impl.ClassloaderChangeLog
import net.corda.db.admin.impl.LiquibaseSchemaMigratorImpl
import net.corda.db.schema.DbSchema
import net.corda.db.testkit.DbUtils
import net.corda.libs.cpi.datamodel.CpiCpkEntity
import net.corda.libs.cpi.datamodel.CpiCpkKey
import net.corda.libs.cpi.datamodel.CpiEntities
import net.corda.libs.cpi.datamodel.CpiMetadataEntity
import net.corda.libs.cpi.datamodel.CpiMetadataEntityKey
import net.corda.libs.cpi.datamodel.CpkFileEntity
import net.corda.libs.cpi.datamodel.CpkKey
import net.corda.libs.cpi.datamodel.CpkMetadataEntity
import net.corda.libs.packaging.Cpi
import net.corda.libs.packaging.Cpk
import net.corda.libs.packaging.core.CordappManifest
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.libs.packaging.core.CpiMetadata
import net.corda.libs.packaging.core.CpkFormatVersion
import net.corda.libs.packaging.core.CpkIdentifier
import net.corda.libs.packaging.core.CpkManifest
import net.corda.libs.packaging.core.CpkMetadata
import net.corda.libs.packaging.core.CpkType
import net.corda.libs.packaging.core.ManifestCorDappInfo
import net.corda.orm.impl.EntityManagerFactoryFactoryImpl
import net.corda.orm.utils.transaction
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DatabaseCpiPersistenceTest {
    companion object {
        private const val MIGRATION_FILE_LOCATION = "net/corda/db/schema/config/db.changelog-master.xml"
    }

    // N.B.  We're pulling in the config tables as well.
    private val emConfig = DbUtils.getEntityManagerConfiguration("chunking_db_for_test")
    private val entityManagerFactory = EntityManagerFactoryFactoryImpl().create(
        "test_unit",
        ChunkingEntities.classes.toList() + CpiEntities.classes.toList(),
        emConfig
    )
    private val cpiPersistence = DatabaseCpiPersistence(entityManagerFactory)
    private val mockCpkContent = """
            Lorem ipsum dolor sit amet, consectetur adipiscing elit. Proin id mauris ut tortor 
            condimentum porttitor. Praesent commodo, ipsum vitae malesuada placerat, nisl sem 
            ornare nibh, id rutrum mi elit in metus. Sed ac tincidunt elit. Aliquam quis 
            pellentesque lacus. Quisque commodo tristique pellentesque. Nam sodales, urna id 
            convallis condimentum, nulla lacus vestibulum ipsum, et ultrices sem magna sed neque. 
            Pellentesque id accumsan odio, non interdum nibh. Nullam lacinia vestibulum purus, 
            finibus maximus enim scelerisque eu. Ut nibh lacus, semper eget cursus a, porttitor 
            eu odio. Vivamus vel placerat eros, sed convallis est. Proin tristique ut odio at 
            finibus. 
        """.trimIndent()

    /**
     * Creates an in-memory database, applies the relevant migration scripts, and initialises
     * [entityManagerFactory].
     */
    init {
        val dbChange = ClassloaderChangeLog(
            linkedSetOf(
                ClassloaderChangeLog.ChangeLogResourceFiles(
                    DbSchema::class.java.packageName,
                    listOf(MIGRATION_FILE_LOCATION),
                    DbSchema::class.java.classLoader
                )
            )
        )
        emConfig.dataSource.connection.use { connection ->
            LiquibaseSchemaMigratorImpl().updateDb(connection, dbChange)
        }
    }

    @Suppress("Unused")
    @AfterAll
    fun cleanup() {
        emConfig.close()
        entityManagerFactory.close()
    }

    lateinit var fs: FileSystem

    @BeforeEach
    private fun beforeEach() {
        fs = Jimfs.newFileSystem()
    }

    @AfterEach
    private fun afterEach() {
        fs.close()
    }

    private fun String.writeToPath(): Path {
        val path = fs.getPath(UUID.randomUUID().toString())
        Files.writeString(path, this)
        return path
    }

    private fun updatedCpk(cpkId: CpkIdentifier, newFileChecksum: SecureHash = newRandomSecureHash()) =
        mockCpk(cpkId.name, newFileChecksum, cpkId.signerSummaryHash)

    private fun mockCpk(
        name: String,
        fileChecksum: SecureHash = newRandomSecureHash(),
        cpkSignerSummaryHash: SecureHash? = newRandomSecureHash()
    ) = mock<Cpk>().also { cpk ->
        val cpkId = CpkIdentifier(
            name = name,
            version = "cpk-version",
            signerSummaryHash = cpkSignerSummaryHash
        )

        val cpkManifest = CpkManifest(CpkFormatVersion(1, 0))

        val cordappManifest = CordappManifest(
            "", "", -1, -1,
            ManifestCorDappInfo(null, null, null, null),
            ManifestCorDappInfo(null, null, null, null),
            emptyMap()
        )

        val metadata = CpkMetadata(
            cpkId = cpkId,
            manifest = cpkManifest,
            mainBundle = "main-bundle",
            libraries = emptyList(),
            dependencies = emptyList(),
            cordappManifest = cordappManifest,
            type = CpkType.UNKNOWN,
            fileChecksum = fileChecksum,
            cordappCertificates = emptySet(),
            timestamp = Instant.now()
        )
        whenever(cpk.path).thenReturn(mockCpkContent.writeToPath())
        whenever(cpk.originalFileName).thenReturn(name)
        whenever(cpk.metadata).thenReturn(metadata)
    }

    private fun mockCpi(cpks: Collection<Cpk>): Cpi {
        // We need a random name here as the database primary key is (name, version, signerSummaryHash)
        // and we'd end up trying to insert the same mock cpi.
        val id = mock<CpiIdentifier> {
            whenever(it.name).thenReturn("test " + UUID.randomUUID().toString())
            whenever(it.version).thenReturn("1.0")
            whenever(it.signerSummaryHash).thenReturn(SecureHash("SHA-256", ByteArray(12)))
        }

        return mockCpiWithId(cpks, id)
    }

    private fun mockCpiWithId(cpks: Collection<Cpk>, cpiId: CpiIdentifier): Cpi {
        val metadata = mock<CpiMetadata>().also {
            whenever(it.cpiId).thenReturn(cpiId)
            whenever(it.groupPolicy).thenReturn("{}")
        }

        val cpi = mock<Cpi>().also {
            whenever(it.cpks).thenReturn(cpks)
            whenever(it.metadata).thenReturn(metadata)
        }

        return cpi
    }

    /**
     * Various db tools show a persisted cpk (or bytes) as just a textual 'handle' to the blob of bytes,
     * so explicitly test here that it's actually doing what we think it is (persisting the bytes!).
     */
    @Test
    fun `database cpi persistence writes data and can be read back`() {
        val checksum = newRandomSecureHash()
        val cpks = listOf(mockCpk("${UUID.randomUUID()}.cpk", checksum))
        val cpi = mockCpi(cpks)

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            checksum,
            UUID.randomUUID().toString(),
            "abcdef",
            emptyList()
        )

        val query = "FROM ${CpkFileEntity::class.simpleName} where fileChecksum = :cpkFileChecksum"
        val cpkDataEntity = entityManagerFactory.createEntityManager().transaction {
            it.createQuery(query, CpkFileEntity::class.java)
                .setParameter("cpkFileChecksum", checksum.toString())
                .singleResult
        }!!

        assertThat(cpkDataEntity.data).isEqualTo(mockCpkContent.toByteArray())
    }

    @Test
    fun `database cpi persistence can lookup persisted cpi by checksum`() {
        val checksum = newRandomSecureHash()
        assertThat(cpiPersistence.cpkExists(checksum)).isFalse

        val cpks = listOf(mockCpk("${UUID.randomUUID()}.cpk", checksum))
        val cpi = mockCpi(cpks)
        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "someFileName.cpi",
            checksum,
            UUID.randomUUID().toString(),
            "abcdef",
            emptyList()
        )
        assertThat(cpiPersistence.cpkExists(checksum)).isTrue
    }

    private val random = Random(0)
    private fun newRandomSecureHash(): SecureHash {
        return SecureHash(DigestAlgorithmName.DEFAULT_ALGORITHM_NAME.name, ByteArray(32).also(random::nextBytes))
    }

    @Test
    fun `database cpi persistence can write multiple cpks into database`() {
        val cpks = listOf(
            mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash()),
            mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash()),
            mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash()),
        )
        val checksum = newRandomSecureHash()

        val cpi = mockCpi(cpks)

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            checksum,
            UUID.randomUUID().toString(),
            "123456",
            emptyList()
        )

        assertThrows<PersistenceException> {
            cpiPersistence.persistMetadataAndCpks(
                cpi,
                "test.cpi",
                checksum,
                UUID.randomUUID().toString(),
                "123456",
                emptyList()
            )
        }
    }

    @Test
    fun `database cpi persistence can write multiple CPIs with shared CPKs into database`() {
        val sharedCpkChecksum = newRandomSecureHash()
        val cpk1Checksum = newRandomSecureHash()
        val cpk2Checksum = newRandomSecureHash()

        val sharedCpk = mockCpk("${UUID.randomUUID()}.cpk", sharedCpkChecksum)
        val cpk1 = mockCpk("${UUID.randomUUID()}.cpk", cpk1Checksum)
        val cpi1 = mockCpi(listOf(sharedCpk, cpk1))

        cpiPersistence.persistMetadataAndCpks(
            cpi1,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "123456",
            emptyList()
        )

        val cpk2 = mockCpk("${UUID.randomUUID()}.cpk", cpk2Checksum)
        val cpi2 = mockCpi(listOf(sharedCpk, cpk2))

        assertDoesNotThrow {
            cpiPersistence.persistMetadataAndCpks(
                cpi2,
                "test.cpi",
                newRandomSecureHash(),
                UUID.randomUUID().toString(),
                "123456",
                emptyList()
            )
        }

        findAndAssertCpk(cpi1.metadata.cpiId, sharedCpk.metadata.cpkId, sharedCpkChecksum.toString(), 0, 1, 0)
        findAndAssertCpk(cpi2.metadata.cpiId, sharedCpk.metadata.cpkId, sharedCpkChecksum.toString(), 0, 1, 0)
        findAndAssertCpk(cpi1.metadata.cpiId, cpk1.metadata.cpkId, cpk1Checksum.toString(), 0, 0, 0)
        findAndAssertCpk(cpi2.metadata.cpiId, cpk2.metadata.cpkId, cpk2Checksum.toString(), 0, 0, 0)
    }

    @Test
    fun `database cpi persistence can force update a CPI`() {
        val cpkChecksum = newRandomSecureHash()
        val cpk1 = mockCpk("${UUID.randomUUID()}.cpk", cpkChecksum)
        val cpi = mockCpi(listOf(cpk1))
        val cpiFileName = "test${UUID.randomUUID()}.cpi"

        val cpiMetadataEntity =
            cpiPersistence.persistMetadataAndCpks(
                cpi,
                cpiFileName,
                newRandomSecureHash(),
                UUID.randomUUID().toString(),
                "abcdef",
                emptyList()
            )

        assertThat(cpiMetadataEntity.entityVersion).isEqualTo(1)
        assertThat(cpiMetadataEntity.cpks.size).isEqualTo(1)
        assertThat(cpiMetadataEntity.cpks.first().entityVersion).isEqualTo(0)

        // make same assertions but after loading the entity again
        val initialLoadedCpi = entityManagerFactory.createEntityManager().transaction {
            it.find(
                CpiMetadataEntity::class.java, CpiMetadataEntityKey(
                    cpi.metadata.cpiId.name,
                    cpi.metadata.cpiId.version,
                    cpi.metadata.cpiId.signerSummaryHash.toString(),
                )
            )
        }!!

        // adding cpk to cpi accounts for 1 modification
        assertThat(initialLoadedCpi.entityVersion).isEqualTo(1)
        assertThat(initialLoadedCpi.cpks.size).isEqualTo(1)
        assertThat(initialLoadedCpi.cpks.first().entityVersion).isEqualTo(0)

        val updatedCpkChecksum = newRandomSecureHash()
        val updatedCpks = listOf(cpk1, mockCpk("${UUID.randomUUID()}.cpk", updatedCpkChecksum))
        // cpi with different CPKs but same ID
        val updatedCpi = mockCpiWithId(updatedCpks, cpi.metadata.cpiId)

        val returnedCpiMetadataEntity =
            cpiPersistence.updateMetadataAndCpks(
                updatedCpi,
                cpiFileName,
                newRandomSecureHash(),
                UUID.randomUUID().toString(),
                "abcdef",
                emptyList()
            )

        assertThat(returnedCpiMetadataEntity.entityVersion).isEqualTo(3)
        val firstReturnedCpk = returnedCpiMetadataEntity.cpks.first { it.cpkFileChecksum == cpkChecksum.toString() }
        val secondReturnedCpk = returnedCpiMetadataEntity.cpks.first { it.cpkFileChecksum == updatedCpkChecksum.toString() }
        assertThat(firstReturnedCpk.entityVersion).isEqualTo(0)
        assertThat(secondReturnedCpk.entityVersion).isEqualTo(0)

        // make same assertions but after loading the entity again
        val updatedLoadedCpi = entityManagerFactory.createEntityManager().transaction {
            it.find(
                CpiMetadataEntity::class.java, CpiMetadataEntityKey(
                    updatedCpi.metadata.cpiId.name,
                    updatedCpi.metadata.cpiId.version,
                    updatedCpi.metadata.cpiId.signerSummaryHash.toString(),
                )
            )
        }!!

        assertThat(updatedLoadedCpi.cpks.size).isEqualTo(2)
        assertThat(updatedLoadedCpi.entityVersion).isEqualTo(3)
        val firstCpk = updatedLoadedCpi.cpks.first { it.cpkFileChecksum == cpkChecksum.toString() }
        val secondCpk = updatedLoadedCpi.cpks.first { it.cpkFileChecksum == updatedCpkChecksum.toString() }
        assertThat(firstCpk.entityVersion).isEqualTo(0)
        assertThat(secondCpk.entityVersion).isEqualTo(0)
    }

    @Test
    fun `database cpi persistence can force update the same CPI`() {
        val cpiChecksum = newRandomSecureHash()
        val cpkChecksum = newRandomSecureHash()
        val cpk1 = mockCpk("${UUID.randomUUID()}.cpk", cpkChecksum)
        val cpks = listOf(cpk1)
        val cpi = mockCpi(cpks)

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            cpiChecksum,
            UUID.randomUUID().toString(),
            "abcdef",
            emptyList()
        )

        val loadedCpi = entityManagerFactory.createEntityManager().transaction {
            it.find(
                CpiMetadataEntity::class.java, CpiMetadataEntityKey(
                    cpi.metadata.cpiId.name,
                    cpi.metadata.cpiId.version,
                    cpi.metadata.cpiId.signerSummaryHash.toString(),
                )
            )
        }!!

        // adding cpk to cpi accounts for 1 modification
        assertThat(loadedCpi.entityVersion).isEqualTo(1)
        assertThat(loadedCpi.cpks.size).isEqualTo(1)
        assertThat(loadedCpi.cpks.first().entityVersion).isEqualTo(0)

        // force update same CPI
        cpiPersistence.updateMetadataAndCpks(
            cpi,
            "test.cpi",
            cpiChecksum,
            UUID.randomUUID().toString(),
            "abcdef",
            emptyList()
        )

        val updatedCpi = entityManagerFactory.createEntityManager().transaction {
            it.find(
                CpiMetadataEntity::class.java, CpiMetadataEntityKey(
                    cpi.metadata.cpiId.name,
                    cpi.metadata.cpiId.version,
                    cpi.metadata.cpiId.signerSummaryHash.toString(),
                )
            )
        }!!

        assertThat(updatedCpi.insertTimestamp).isAfter(loadedCpi.insertTimestamp)
        // merging updated cpi accounts for 1 modification + modifying cpk
        assertThat(updatedCpi.entityVersion).isEqualTo(3)
        assertThat(updatedCpi.cpks.size).isEqualTo(1)
        assertThat(updatedCpi.cpks.first().entityVersion).isEqualTo(0)
    }

    @Test
    fun `CPKs are correct after persisting a CPI with already existing CPK`() {
        val sharedCpk = mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash())
        val cpi = mockCpi(listOf(sharedCpk))

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-a",
            emptyList()
        )

        val cpi2 = mockCpi(listOf(sharedCpk))

        cpiPersistence.persistMetadataAndCpks(
            cpi2,
            "test2.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-b",
            emptyList()
        )

        findAndAssertCpk(
            cpi.metadata.cpiId,
            sharedCpk.metadata.cpkId,
            sharedCpk.metadata.fileChecksum.toString(),
            0,
            1,
            0
        )
        findAndAssertCpk(
            cpi2.metadata.cpiId,
            sharedCpk.metadata.cpkId,
            sharedCpk.metadata.fileChecksum.toString(),
            0,
            1,
            0
        )
    }

    @Test
    fun `CPKs are correct after updating a CPI by adding a new CPK`() {
        val cpk = mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash())
        val newCpk = mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash())
        val cpi = mockCpi(listOf(cpk))

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-a",
            emptyList()
        )

        // a new cpi object, but with same
        val updatedCpi = mockCpiWithId(listOf(cpk, newCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpks(
            updatedCpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-b",
            emptyList()
        )

        assertThat(cpi.metadata.cpiId).isEqualTo(updatedCpi.metadata.cpiId)

        findAndAssertCpk(cpi.metadata.cpiId, cpk.metadata.cpkId, cpk.metadata.fileChecksum.toString(), 0, 1, 0)
        findAndAssertCpk(cpi.metadata.cpiId, newCpk.metadata.cpkId, newCpk.metadata.fileChecksum.toString(), 0, 0, 0)
    }

    @Test
    fun `CPK version is incremented when we update a CPK in a CPI`() {
        val cpk = mockCpk("${UUID.randomUUID()}.cpk", newRandomSecureHash())
        val newChecksum = newRandomSecureHash()
        val updatedCpk = updatedCpk(cpk.metadata.cpkId, newChecksum)
        val cpi = mockCpi(listOf(cpk))

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-a",
            emptyList()
        )

        // a new cpi object, but with same
        val updatedCpi = mockCpiWithId(listOf(updatedCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpks(
            updatedCpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-b",
            emptyList()
        )

        assertThat(cpi.metadata.cpiId).isEqualTo(updatedCpi.metadata.cpiId)

        findAndAssertCpk(cpi.metadata.cpiId, cpk.metadata.cpkId, newChecksum.toString(), 1, 2, 1)
    }

    @Test
    fun `CPK version is incremented when CpiCpkEntity has non-zero entityversion`() {
        val firstCpkChecksum = newRandomSecureHash()
        val cpk = mockCpk("${UUID.randomUUID()}.cpk", firstCpkChecksum)
        val cpi = mockCpi(listOf(cpk))

        cpiPersistence.persistMetadataAndCpks(
            cpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-a",
            emptyList()
        )

        findAndAssertCpk(cpi.metadata.cpiId, cpk.metadata.cpkId, firstCpkChecksum.toString(), 0, 0, 0)

        // a new cpi object, but with same cpk
        val secondCpkChecksum = newRandomSecureHash()
        val updatedCpk = updatedCpk(cpk.metadata.cpkId, secondCpkChecksum)
        val updatedCpi = mockCpiWithId(listOf(updatedCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpks(
            updatedCpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-b",
            emptyList()
        )

        findAndAssertCpk(cpi.metadata.cpiId, cpk.metadata.cpkId, secondCpkChecksum.toString(), 1, 2, 1)

        // a new cpi object, but with same cpk
        val thirdChecksum = newRandomSecureHash()
        val anotherUpdatedCpk = updatedCpk(cpk.metadata.cpkId, thirdChecksum)
        val anotherUpdatedCpi = mockCpiWithId(listOf(anotherUpdatedCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpks(
            anotherUpdatedCpi,
            "test.cpi",
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            "group-b",
            emptyList()
        )

        findAndAssertCpk(cpi.metadata.cpiId, cpk.metadata.cpkId, thirdChecksum.toString(), 2, 4, 2)
    }

    private fun findAndAssertCpk(
        cpiId: CpiIdentifier,
        cpkId: CpkIdentifier,
        expectedCpkFileChecksum: String,
        expectedMetadataEntityVersion: Int,
        expectedFileEntityVersion: Int,
        expectedCpiCpkEntityVersion: Int
    ) {
        val (cpkMetadata, cpkFile, cpiCpk) = entityManagerFactory.createEntityManager().transaction {
            val cpiCpkKey = CpiCpkKey(
                cpiId.name,
                cpiId.version,
                cpiId.signerSummaryHash.toString(),
                cpkId.name,
                cpkId.version,
                cpkId.signerSummaryHash.toString()
            )
            val cpkKey = CpkKey(
                cpkId.name,
                cpkId.version,
                cpkId.signerSummaryHash.toString()
            )
            val cpiCpk = it.find(CpiCpkEntity::class.java, cpiCpkKey)
            val cpkMetadata = it.find(CpkMetadataEntity::class.java, cpkKey)
            val cpkFile = it.find(CpkFileEntity::class.java, cpkKey)
            Triple(cpkMetadata, cpkFile, cpiCpk)
        }

        assertThat(cpkMetadata.cpkFileChecksum).isEqualTo(expectedCpkFileChecksum)
        assertThat(cpkFile.fileChecksum).isEqualTo(expectedCpkFileChecksum)

        assertThat(cpkMetadata.entityVersion)
            .withFailMessage("CpkMetadataEntity.entityVersion expected $expectedMetadataEntityVersion but was ${cpkMetadata.entityVersion}.")
            .isEqualTo(expectedMetadataEntityVersion)
        assertThat(cpkFile.entityVersion)
            .withFailMessage("CpkFileEntity.entityVersion expected $expectedFileEntityVersion but was ${cpkFile.entityVersion}.")
            .isEqualTo(expectedFileEntityVersion)
        assertThat(cpiCpk.entityVersion)
            .withFailMessage("CpiCpkEntity.entityVersion expected $expectedCpiCpkEntityVersion but was ${cpiCpk.entityVersion}.")
            .isEqualTo(expectedCpiCpkEntityVersion)
    }
}