package net.corda.messaging.chunking

import java.nio.ByteBuffer
import java.util.UUID
import net.corda.chunking.Checksum
import net.corda.chunking.ChunkBuilderService
import net.corda.chunking.Constants.Companion.APP_LEVEL_CHUNK_MESSAGE_OVERHEAD
import net.corda.chunking.Constants.Companion.CORDA_RECORD_OVERHEAD
import net.corda.crypto.cipher.suite.PlatformDigestService
import net.corda.data.CordaAvroSerializer
import net.corda.data.chunking.Chunk
import net.corda.data.chunking.ChunkKey
import net.corda.messagebus.api.producer.CordaProducerRecord
import net.corda.messaging.api.chunking.ChunkSerializerService
import net.corda.v5.base.util.debug
import net.corda.v5.base.util.trace
import net.corda.v5.crypto.DigestAlgorithmName
import org.slf4j.LoggerFactory

/**
 * Breaks up an object, bytes or record into chunks.
 */
class ChunkSerializerServiceImpl(
    maxAllowedMessageSize: Long,
    private val cordaAvroSerializer: CordaAvroSerializer<Any>,
    private val chunkBuilderService: ChunkBuilderService,
    private val platformDigestService: PlatformDigestService
) : ChunkSerializerService {

    companion object {
        const val INITIAL_PART_NUMBER = 1
        private val logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    // chunk size must be smaller than the max allowed message size to allow a buffer for the rest of the message.
    private val maxRecordSize = (maxAllowedMessageSize - CORDA_RECORD_OVERHEAD).toInt()
    private val maxChunkSize = (maxAllowedMessageSize - APP_LEVEL_CHUNK_MESSAGE_OVERHEAD).toInt()

    override fun generateChunks(anyObject: Any): List<Chunk> {
        logger.debug { "Generating chunks for object of type ${anyObject::class.java}" }
        val bytes = tryToSerialize(anyObject)
        if (bytes == null || bytes.size <= maxChunkSize) {
            return emptyList()
        }
        return generateChunksFromBytes(bytes, maxChunkSize)
    }

    override fun generateChunkedRecords(producerRecord: CordaProducerRecord<*, *>): List<CordaProducerRecord<*, *>> {
        val serializedKey = tryToSerialize(producerRecord.key)
        val valueBytes = tryToSerialize(producerRecord.value)
        if (serializedKey == null || valueBytes == null || valueBytes.size <= maxRecordSize) {
            return emptyList()
        }

        val chunksToKey = generateChunksFromBytes(valueBytes, maxRecordSize).associateBy {
            ChunkKey.newBuilder()
                .setPartNumber(it.partNumber)
                .setRealKey(ByteBuffer.wrap(serializedKey))
                .setRequestId(it.requestId)
                .build()
        }

        return chunksToKey.map { CordaProducerRecord(producerRecord.topic, it.key, it.value) }
    }

    private fun generateChunksFromBytes(bytes: ByteArray, chunkSize: Int): List<Chunk> {
        val byteSize = bytes.size
        val hash = platformDigestService.hash(bytes, DigestAlgorithmName(Checksum.ALGORITHM))
        var partNumber = INITIAL_PART_NUMBER
        val requestId = UUID.randomUUID().toString()

        val chunks = mutableListOf<Chunk>()
        for (offset in bytes.indices step chunkSize) {
            val length = kotlin.math.min(chunkSize, bytes.size - offset)
            val byteBuffer = ByteBuffer.wrap(bytes, offset, length)
            chunks.add(chunkBuilderService.buildChunk(requestId, partNumber++, byteBuffer, offset.toLong()))
        }
        chunks.add(chunkBuilderService.buildFinalChunk(requestId, partNumber, hash, byteSize.toLong()-1))
        logger.trace { "Generating chunks for bytes size $byteSize, chunk id ${chunks.first().requestId}" }

        return chunks
    }

    /**
     * Try to serialize an object. Swallow any exceptions thrown. These will be caught and handled by the kafka client on send
     * @param obj object to serialize
     * @return the serialized object as a ByteArray. Returns null if serialization fails or the object was null.
     */
    private fun tryToSerialize(obj: Any?) : ByteArray? {
        if (obj == null) return null
        return try {
            cordaAvroSerializer.serialize(obj)
        } catch (ex: Throwable) {
            // if serialization is going to fail, let it be handled within the kafka client logic
            return null
        }
    }
}
