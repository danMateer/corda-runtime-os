package net.corda.applications.workers.smoketest.virtualnode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.applications.workers.smoketest.virtualnode.helpers.SimpleResponse
import net.corda.applications.workers.smoketest.virtualnode.helpers.cluster
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.URI
import java.time.Duration

// The CPI we're using in this test
const val CALCULATOR_CPI = "/META-INF/calculator.cpb"

fun SimpleResponse.toJson(): JsonNode = ObjectMapper().readTree(this.body!!)!!
fun String.toJson(): JsonNode = ObjectMapper().readTree(this)

// BUG:  Not sure if we should be requiring clients to use a method similar to this because we
// return a full hash (64 chars?) but the same API only accepts the first 12 chars.
fun String.toShortHash(): String = substring(0, 12)

/**
 * Requests, and returns if [condition] is met, otherwise backs off and rerequests up to [maxTimeout] and returns
 * final [SimpleResponse].
 *
 * @return a [SimpleResponse] object that *you* need to close
 */
fun requestWithRetryOrTimeOut(
    maxTimeout: Duration,
    condition: (code: Int, body: String) -> Boolean,
    block: () -> SimpleResponse
): SimpleResponse {
    var timeout = Duration.ofMillis(250)
    var response: SimpleResponse?

    do {
        Thread.sleep(timeout.toMillis())
        timeout = timeout.multipliedBy(2)
        response = block()
        if (condition(response.code, response.body!!)) {
            break
        }
    } while (timeout.toMillis() < maxTimeout.toMillis())

    return response!!
}

/**
 * Any 'unordered' tests are run *last*
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VirtualNodeRpcTest {
    companion object {
        // Some simple test failure messages
        private const val ERROR_CPI_NOT_UPLOADED =
            "CPI has not been uploaded during this run - this test needs to be run on a clean cluster."
        private const val ERROR_IS_CLUSTER_RUNNING = "Initial upload failed - is the cluster running?"
        private const val ERROR_HOLDING_ID =
            "Holding id could not be created - this test needs to be run on a clean cluster."

        // Server side messages
        // BUG: These server-side messages can change arbitrarily - not ideal at the moment, but we return all errors with code '500' and no other info.
        private const val EXPECTED_ERROR_NO_GROUP_POLICY = "CPI is missing a group policy file"
        private const val EXPECTED_ERROR_ALREADY_UPLOADED = "CPI already uploaded with groupId"

        // Holding identity(-ies)
        private const val X500_ALICE = "CN=Alice, OU=Application, O=R3, L=London, C=GB"

        // Max wait duration for calls - arbitrarily picked - we might want this higher for slower code,
        // or configurable and injected in.
        private val WAIT_DURATION = Duration.ofMillis(2000L)
    }

    private val clusterUri = URI(System.getProperty("rpcHost"))
    private val username = "admin"
    private val password = "admin"

    private val groupId = "placeholder"

    /**
     * As long as no-one assigns an order lower than this, this test runs first, and all others, after, which is fine.
     *
     * This *first* test, uploads a CPI into the system.  It must pass.
     */
    @Test
    @Order(10)
    fun `can upload CPI`() {
        cluster {
            endpoint(clusterUri, username, password)

            val requestId = cpiUpload(CALCULATOR_CPI, groupId).let { it.toJson()["id"].textValue() }
            assertThat(requestId).withFailMessage(ERROR_IS_CLUSTER_RUNNING).isNotEmpty

            // BUG:  returning "OK" feels 'weakly' typed
            val statusIsOk = { code: Int, body: String -> code == 200 && body.toJson()["status"].textValue() == "OK" }

            val statusResponse = requestWithRetryOrTimeOut(WAIT_DURATION, statusIsOk) { cpiStatus(requestId) }

            val json = statusResponse.let {
                val jsonString = it.body!!
                assertThat(statusIsOk(it.code, jsonString))
                    .withFailMessage("$ERROR_CPI_NOT_UPLOADED\n\n$it")
                    .isTrue
                jsonString.toJson()
            }

            val cpiHash = json["checksum"].textValue()
            assertThat(cpiHash).isNotNull.isNotEmpty

            // Capture the cpiHash from the cpi status upload
            // We probably want more tests like this that enforce "expectations" on the API.
            assertThat(cpiHash!!.length)
                .withFailMessage("Short code length of wrong size - likely this test needs fixing")
                .isEqualTo(12)

            // Compare it to the hash in the cpi list - should be identical.
            // CORE-4475: tests fixed behaviour previously reported as a bug
            val cpis = cpiList().let { it.toJson() }
            val cpiJson = cpis["cpis"].first()
            val actualChecksum = cpiJson["fileChecksum"].textValue().toShortHash()

            assertThat(actualChecksum).isNotNull.isNotEmpty

            assertThat(cpiHash).withFailMessage(ERROR_CPI_NOT_UPLOADED).isNotNull

            assertThat(actualChecksum).isEqualTo(cpiHash)
        }
    }

    /**
     * Runs second to ensure that we reject this with a correct message
     */
    @Test
    @Order(20)
    fun `cannot upload CPI without group policy file aka CPB`() {
        cluster {
            endpoint(clusterUri, username, password)

            val requestId = cpbUpload(CALCULATOR_CPI).let { it.toJson()["id"].textValue() }
            assertThat(requestId).withFailMessage(ERROR_IS_CLUSTER_RUNNING).isNotEmpty

            val statusIsNotOk = { code: Int, _: String -> code == 500 }

            val statusResponse = requestWithRetryOrTimeOut(WAIT_DURATION, statusIsNotOk) { cpiStatus(requestId) }

            val json = statusResponse.let {
                val jsonString = it.body!!
                assertThat(
                    statusIsNotOk(
                        it.code,
                        jsonString
                    )
                ).isTrue // We expect this to change as we improve the error reporting
                jsonString.toJson()
            }

            val titleJson = ObjectMapper().readTree(json["title"].textValue())
            assertThat(titleJson["errorMessage"].textValue()).isEqualTo(EXPECTED_ERROR_NO_GROUP_POLICY)
        }
    }

    @Test
    @Order(30)
    fun `cannot upload same CPI`() {
        cluster {
            endpoint(clusterUri, username, password)
            val requestId = cpiUpload(CALCULATOR_CPI, groupId).let { it.toJson()["id"].textValue() }
            assertThat(requestId).withFailMessage(ERROR_IS_CLUSTER_RUNNING).isNotEmpty

            val statusIsNotOk = { code: Int, _: String -> code == 500 }

            val statusResponse = requestWithRetryOrTimeOut(WAIT_DURATION, statusIsNotOk) { cpiStatus(requestId) }

            val json = statusResponse.let {
                val jsonString = it.body!!
                assertThat(
                    statusIsNotOk(
                        it.code,
                        jsonString
                    )
                ).isTrue // We expect this to change as we improve the error reporting
                jsonString.toJson()
            }

            val titleJson = ObjectMapper().readTree(json["title"].textValue())
            assertThat(titleJson["errorMessage"].textValue().startsWith(EXPECTED_ERROR_ALREADY_UPLOADED)).isTrue()
        }
    }

    @Test
    fun `list cpis`() {
        cluster {
            endpoint(clusterUri, username, password)

            val json = cpiList().let {
                assertThat(it.code).isEqualTo(200)
                it.toJson()
            }
            assertThat(json["cpis"].size()).isGreaterThan(0)
        }
    }

    @Test
    fun `list cpis and check group id matches value in group policy file`() {
        cluster {
            endpoint(clusterUri, username, password)

            val json = cpiList().let { it.toJson() }
            val cpiJson = json["cpis"].first()

            val groupPolicyJson = cpiJson["groupPolicy"].textValue().toJson()
            assertThat(groupPolicyJson["groupId"].textValue()).isEqualTo(groupId)
        }
    }

    @Test
    @Order(40)
    fun `can create virtual node with holding id and CPI`() {
        cluster {
            endpoint(clusterUri, username, password)
            val cpis = cpiList().let { it.toJson() }["cpis"]
            val json = cpis.toList().first { it["id"]["cpiName"].textValue() == "calculator" }
            val hash = json["fileChecksum"].textValue().toShortHash()

            val vNodeJson = vNodeCreate(hash, X500_ALICE).let {
                assertThat(it.code)
                    .withFailMessage("$ERROR_HOLDING_ID\n$it\n${json.toPrettyString()}")
                    .isEqualTo(200)
                it.toJson()
            }

            assertThat(vNodeJson["holdingIdHash"].textValue()).isNotNull.isNotEmpty
        }
    }

    @Test
    @Order(50)
    fun `cannot create duplicate virtual node`() {
        cluster {
            endpoint(clusterUri, username, password)
            val cpis = cpiList().let { it.toJson() }["cpis"]
            val json = cpis.toList().first { it["id"]["cpiName"].textValue() == "calculator" }
            val hash = json["fileChecksum"].textValue().toShortHash()

            vNodeCreate(hash, X500_ALICE).let {
                assertThat(it.code).isEqualTo(500) // This return code may change.
            }
        }
    }

    @Test
    @Order(60)
    fun `list virtual nodes`() {
        cluster {
            endpoint(clusterUri, username, password)
            val json = vNodeList().let { it.toJson() }["virtualNodes"].first()
            val actualX500Name = json["holdingIdentity"]["x500Name"].textValue()

            assertThat(actualX500Name).isEqualTo(X500_ALICE)
        }
    }

    @Test
    @Order(70)
    fun `run calculator flow`() {
        cluster {
            endpoint(clusterUri, username, password)

            val vnJson = vNodeList().let { it.toJson() }["virtualNodes"].first()
            val id = vnJson["holdingIdentity"]["id"].textValue()

            val counter = 1

            val a = 10
            val b = 20
            val requestBody = """{ "requestBody":  "{ \"a\":$a, \"b\":$b }" }"""

            // BUG:  Due to a bug in the flow worker, we *still* have to wait for a while for the CPI to be assembled
            // otherwise the flow-worker just stops responding if a flow is requested before the CPI.
            Thread.sleep(WAIT_DURATION.multipliedBy(5L).toMillis())

            // Depends on the flows in the cpi
            val className = "net.corda.testing.calculator.CalculatorFlow"
            flowStart(id, 1, className, requestBody).let {
                assertThat(it.code)
                    .withFailMessage("when calling ${it.url}")
                    .isEqualTo(200)
            }

            val flowFinished =
                { code: Int, body: String -> code == 200 && body.toJson()["flowStatus"].textValue() == "COMPLETED" }

            val flowStatusResponse = requestWithRetryOrTimeOut(WAIT_DURATION, flowFinished) { flowStatus(id, counter) }

            val json = flowStatusResponse.let {
                val jsonString = it.body!!
                assertThat(flowFinished(it.code, jsonString)).isTrue
                jsonString.toJson()
            }

            // Depends on the keys in the test cpi
            val resultJson = ObjectMapper().readTree(json["flowResult"].textValue())
            assertThat(resultJson["result"].intValue()).isEqualTo(a + b)
        }
    }
}