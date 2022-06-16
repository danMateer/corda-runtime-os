package net.corda.libs.packaging.verify.internal.cpk

import net.corda.libs.packaging.JarReader
import net.corda.libs.packaging.PackagingConstants
import net.corda.libs.packaging.PackagingConstants.CPK_FORMAT_ATTRIBUTE
import net.corda.libs.packaging.PackagingConstants.CPK_LIB_FOLDER
import net.corda.libs.packaging.PackagingConstants.CPK_LICENCE_ATTRIBUTE
import net.corda.libs.packaging.PackagingConstants.CPK_NAME_ATTRIBUTE
import net.corda.libs.packaging.PackagingConstants.CPK_VENDOR_ATTRIBUTE
import net.corda.libs.packaging.PackagingConstants.CPK_VERSION_ATTRIBUTE
import net.corda.libs.packaging.certSummaryHash
import net.corda.libs.packaging.core.CpkIdentifier
import net.corda.libs.packaging.core.exception.PackagingException
import net.corda.libs.packaging.verify.internal.requireAttribute
import net.corda.libs.packaging.verify.internal.requireAttributeValueIn
import net.corda.libs.packaging.verify.internal.singleOrThrow
import java.util.jar.JarEntry

/**
 * Verifies CPK format 1.0
 */
class CpkV1Verifier(jarReader: JarReader): CpkVerifier {
    private val name = jarReader.jarName
    private val manifest = jarReader.manifest
    private val codeSigners = jarReader.codeSigners
    private val libraries: List<CpkLibrary>
    private val mainBundle: CpkV1MainBundle
    override val id: CpkIdentifier
        get() {
            val certificates = codeSigners.map { it.signerCertPath.certificates.first() }.toSet()
            val cpkSummaryHash = certificates.asSequence().certSummaryHash()
            with (manifest.mainAttributes) {
                return CpkIdentifier(getValue(CPK_NAME_ATTRIBUTE), getValue(CPK_VERSION_ATTRIBUTE), cpkSummaryHash)
            }
        }
    override val dependencies: CpkDependencies
        get() = mainBundle.cpkDependencies

    init {
        libraries = jarReader.entries.filter(::isLibrary).map { CpkLibrary(it.name, it.createInputStream()) }
        mainBundle = jarReader.entries.filter(::isMainBundle).map{ CpkV1MainBundle(it.createJarReader()) }
            .singleOrThrow(
                PackagingException("CorDapp JAR not found in CPK \"$name\""),
                PackagingException("Multiple CorDapp JARs found in CPK \"$name\""))
    }

    private fun isMainBundle(entry: JarReader.Entry): Boolean {
        return entry.name.let {
            it.indexOf('/') == -1 &&
            it.endsWith(PackagingConstants.JAR_FILE_EXTENSION, ignoreCase = true)
        }
    }

    /** Checks whether [JarEntry] is a library JAR */
    private fun isLibrary(entry: JarReader.Entry): Boolean {
        return entry.name.let {
            it.startsWith(CPK_LIB_FOLDER) &&
            it.indexOf('/') == CPK_LIB_FOLDER.length &&
            it.indexOf('/', CPK_LIB_FOLDER.length + 1) == -1 &&
            it.endsWith(PackagingConstants.JAR_FILE_EXTENSION, ignoreCase = true)
        }
    }

    private fun verifyManifest() {
        with (manifest) {
            requireAttributeValueIn(CPK_FORMAT_ATTRIBUTE, "1.0")
            requireAttribute(CPK_NAME_ATTRIBUTE)
            requireAttribute(CPK_VERSION_ATTRIBUTE)
            requireAttribute(CPK_LICENCE_ATTRIBUTE)
            requireAttribute(CPK_VENDOR_ATTRIBUTE)
        }
    }

    private fun verifyLibraries() {
        mainBundle.libraryConstraints.verify(libraries)
    }

    override fun verify() {
        verifyManifest()
        mainBundle.verify()
        verifyLibraries()
    }
}