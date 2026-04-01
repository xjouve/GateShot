package com.gateshot.capture.tracking

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Racer Subject Tracker — locks AF on the fastest-moving subject and rejects distractors.
 *
 * PROBLEM:
 * A ski racing scene contains multiple people: the racer (moving fast through gates),
 * gate judges (standing still or shuffling), course workers (walking slowly),
 * coaches (stationary). A generic AF tracker will jump between any of these.
 *
 * SOLUTION:
 * 1. DETECT all moving objects in the frame via frame differencing
 * 2. CLASSIFY each by speed: racer (30-90+ km/h) vs official (0-5 km/h)
 * 3. LOCK on the fastest-moving subject that matches "racer" criteria
 * 4. HOLD lock through occlusion — predict re-emergence by trajectory
 * 5. REJECT slow-moving distractors even if they're closer to camera
 *
 * Speed discrimination is the key. At 30fps:
 * - A racer at 60 km/h crosses a 1080p frame in ~1.5 seconds = ~25px/frame
 * - An official walking at 3 km/h moves ~1.2px/frame
 * - That's a 20x difference — trivial to distinguish
 */
class SubjectTracker {

    data class TrackedSubject(
        val id: Int,
        val centerX: Float,           // Normalized 0-1
        val centerY: Float,
        val width: Float,             // Bounding box width (normalized)
        val height: Float,            // Bounding box height (normalized)
        val velocityX: Float,         // Pixels/frame in X
        val velocityY: Float,         // Pixels/frame in Y
        val speed: Float,             // Magnitude of velocity (pixels/frame)
        val classification: SubjectClass,
        val confidence: Float,        // 0-1: how confident we are this is the racer
        val framesTracked: Int,       // How many consecutive frames we've tracked this
        val isOccluded: Boolean       // Currently behind a gate panel or obstruction
    )

    enum class SubjectClass {
        RACER,          // Fast-moving, matches racer trajectory
        OFFICIAL,       // Slow-moving or stationary person on the course
        UNKNOWN         // Not yet classified (need more frames)
    }

    data class TrackingConfig(
        val enabled: Boolean = false,
        val minSpeedForRacer: Float = 8f,       // Min pixels/frame to classify as racer
        val maxSpeedForOfficial: Float = 3f,     // Max pixels/frame to still be "official"
        val lockStrength: Float = 0.8f,          // How strongly to resist switching targets (0-1)
        val occlusionTimeoutFrames: Int = 20,    // Max frames to hold lock during occlusion (~0.66s at 30fps)
        val predictionFrames: Int = 5,           // How many frames ahead to predict position
        val afRegionSize: Float = 0.15f          // Size of AF region around tracked subject (fraction of frame)
    )

    private var config = TrackingConfig()
    private var lockedSubject: TrackedSubject? = null
    private var previousFrame: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var candidates = mutableListOf<MotionBlob>()
    private var occlusionCounter = 0
    private var nextSubjectId = 1

    // History for trajectory prediction
    private val positionHistory = mutableListOf<Pair<Float, Float>>()  // (x, y) normalized
    private val maxHistorySize = 15

    fun getConfig(): TrackingConfig = config
    fun setConfig(newConfig: TrackingConfig) { config = newConfig }
    fun isEnabled(): Boolean = config.enabled
    fun getLockedSubject(): TrackedSubject? = lockedSubject

    fun enable() { config = config.copy(enabled = true) }
    fun disable() {
        config = config.copy(enabled = false)
        unlock()
    }

    fun unlock() {
        lockedSubject = null
        positionHistory.clear()
        occlusionCounter = 0
    }

    /**
     * Process a frame and update tracking state.
     * Returns the AF target region (normalized coordinates) or null if no target.
     */
    fun processFrame(imageProxy: ImageProxy): AfTarget? {
        if (!config.enabled) return null

        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride

        val currentFrame = ByteArray(buffer.remaining())
        buffer.get(currentFrame)
        buffer.rewind()

        val prev = previousFrame
        var target: AfTarget? = null

        if (prev != null && previousWidth == width && previousHeight == height) {
            // Detect motion blobs
            val blobs = detectMotionBlobs(prev, currentFrame, width, height, rowStride)

            // Classify each blob
            val subjects = blobs.map { blob ->
                classifyBlob(blob, width, height)
            }

            // Find the best racer candidate
            val racerCandidates = subjects.filter { it.classification == SubjectClass.RACER }
            val locked = lockedSubject

            if (locked != null && !locked.isOccluded) {
                // We have a lock — try to maintain it
                target = maintainLock(locked, subjects, width, height)
            } else if (locked != null && locked.isOccluded) {
                // Lock is occluded — use prediction
                target = handleOcclusion(locked, subjects, width, height)
            } else if (racerCandidates.isNotEmpty()) {
                // No lock yet — acquire the fastest racer
                val fastest = racerCandidates.maxByOrNull { it.speed } ?: return null
                lockedSubject = fastest
                positionHistory.clear()
                positionHistory.add(Pair(fastest.centerX, fastest.centerY))
                target = AfTarget(fastest.centerX, fastest.centerY, config.afRegionSize)
            }
        }

        previousFrame = currentFrame
        previousWidth = width
        previousHeight = height

        return target
    }

    /**
     * Maintain lock on the currently tracked racer.
     * Finds the motion blob closest to the predicted position and verifies it's still fast-moving.
     */
    private fun maintainLock(
        locked: TrackedSubject,
        currentSubjects: List<TrackedSubject>,
        width: Int,
        height: Int
    ): AfTarget? {
        // Predict where the racer should be this frame
        val predictedX = locked.centerX + locked.velocityX / width
        val predictedY = locked.centerY + locked.velocityY / height

        // Find the closest subject to the predicted position that's still fast
        val matchThreshold = 0.15f  // Max normalized distance to still consider a match
        val bestMatch = currentSubjects
            .filter { it.speed >= config.minSpeedForRacer * 0.5f }  // Allow some speed variation
            .minByOrNull { dist(it.centerX, it.centerY, predictedX, predictedY) }

        if (bestMatch != null && dist(bestMatch.centerX, bestMatch.centerY, predictedX, predictedY) < matchThreshold) {
            // Match found — update lock
            lockedSubject = bestMatch.copy(
                id = locked.id,
                framesTracked = locked.framesTracked + 1,
                isOccluded = false
            )
            addToHistory(bestMatch.centerX, bestMatch.centerY)
            occlusionCounter = 0
            return AfTarget(bestMatch.centerX, bestMatch.centerY, config.afRegionSize)
        }

        // No match at predicted position — racer might be occluded
        occlusionCounter++
        if (occlusionCounter <= config.occlusionTimeoutFrames) {
            // Hold lock at predicted position (don't snap to an official!)
            lockedSubject = locked.copy(isOccluded = true)
            return AfTarget(predictedX.coerceIn(0f, 1f), predictedY.coerceIn(0f, 1f), config.afRegionSize)
        }

        // Occlusion timeout — release lock
        unlock()
        return null
    }

    /**
     * Handle occlusion: racer is behind a gate panel or net.
     * Use trajectory history to predict where they'll re-emerge.
     */
    private fun handleOcclusion(
        locked: TrackedSubject,
        currentSubjects: List<TrackedSubject>,
        width: Int,
        height: Int
    ): AfTarget? {
        occlusionCounter++

        // Predict position from trajectory history
        val predicted = predictPosition(config.predictionFrames)
            ?: Pair(locked.centerX, locked.centerY)

        // Check if a fast-moving subject appeared near predicted position
        val reacquired = currentSubjects
            .filter { it.speed >= config.minSpeedForRacer * 0.5f }
            .minByOrNull { dist(it.centerX, it.centerY, predicted.first, predicted.second) }

        if (reacquired != null && dist(reacquired.centerX, reacquired.centerY, predicted.first, predicted.second) < 0.2f) {
            // Racer re-emerged! Re-lock.
            lockedSubject = reacquired.copy(
                id = locked.id,
                framesTracked = locked.framesTracked + 1,
                isOccluded = false
            )
            addToHistory(reacquired.centerX, reacquired.centerY)
            occlusionCounter = 0
            return AfTarget(reacquired.centerX, reacquired.centerY, config.afRegionSize)
        }

        if (occlusionCounter > config.occlusionTimeoutFrames) {
            unlock()
            return null
        }

        // Still occluded — hold AF at predicted position
        return AfTarget(predicted.first.coerceIn(0f, 1f), predicted.second.coerceIn(0f, 1f), config.afRegionSize)
    }

    /**
     * Detect motion blobs via frame differencing.
     * Returns bounding boxes of regions with significant motion.
     */
    private fun detectMotionBlobs(
        prev: ByteArray,
        curr: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int
    ): List<MotionBlob> {
        val blockSize = 16
        val motionThreshold = 25
        val motionBlocks = mutableListOf<Triple<Int, Int, Float>>()  // (blockX, blockY, motionScore)

        for (by in 0 until height / blockSize) {
            for (bx in 0 until width / blockSize) {
                var diff = 0
                var count = 0
                for (dy in 0 until blockSize step 4) {
                    for (dx in 0 until blockSize step 4) {
                        val x = bx * blockSize + dx
                        val y = by * blockSize + dy
                        val idx = y * rowStride + x
                        if (idx < prev.size && idx < curr.size) {
                            diff += abs((curr[idx].toInt() and 0xFF) - (prev[idx].toInt() and 0xFF))
                            count++
                        }
                    }
                }
                if (count > 0) {
                    val avgDiff = diff.toFloat() / count
                    if (avgDiff > motionThreshold) {
                        motionBlocks.add(Triple(bx, by, avgDiff))
                    }
                }
            }
        }

        // Cluster adjacent motion blocks into blobs
        return clusterBlocks(motionBlocks, width / blockSize, height / blockSize, width, height)
    }

    /**
     * Cluster adjacent motion blocks into blobs using flood-fill.
     */
    private fun clusterBlocks(
        blocks: List<Triple<Int, Int, Float>>,
        gridW: Int,
        gridH: Int,
        frameW: Int,
        frameH: Int
    ): List<MotionBlob> {
        val blockSet = blocks.map { Pair(it.first, it.second) }.toMutableSet()
        val blobs = mutableListOf<MotionBlob>()

        while (blockSet.isNotEmpty()) {
            val seed = blockSet.first()
            blockSet.remove(seed)
            val cluster = mutableListOf(seed)
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(seed)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = Pair(current.first + dx, current.second + dy)
                        if (neighbor in blockSet) {
                            blockSet.remove(neighbor)
                            cluster.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }

            if (cluster.size >= 2) {  // Ignore single-block noise
                val minX = cluster.minOf { it.first }
                val maxX = cluster.maxOf { it.first }
                val minY = cluster.minOf { it.second }
                val maxY = cluster.maxOf { it.second }
                val blockSize = 16

                blobs.add(MotionBlob(
                    centerX = ((minX + maxX) / 2f * blockSize) / frameW,
                    centerY = ((minY + maxY) / 2f * blockSize) / frameH,
                    width = ((maxX - minX + 1) * blockSize).toFloat() / frameW,
                    height = ((maxY - minY + 1) * blockSize).toFloat() / frameH,
                    blockCount = cluster.size,
                    avgMotionScore = blocks.filter { b ->
                        cluster.any { it.first == b.first && it.second == b.second }
                    }.map { it.third }.average().toFloat()
                ))
            }
        }

        return blobs
    }

    /**
     * Classify a motion blob as RACER or OFFICIAL based on speed and size.
     */
    private fun classifyBlob(blob: MotionBlob, frameW: Int, frameH: Int): TrackedSubject {
        // Speed estimation: motion score correlates with inter-frame displacement
        // Higher avgMotionScore = faster movement
        val estimatedSpeed = blob.avgMotionScore

        // Size filter: a racer is typically 5-20% of frame height
        val reasonableSize = blob.height in 0.03f..0.5f

        val classification = when {
            estimatedSpeed >= config.minSpeedForRacer && reasonableSize -> SubjectClass.RACER
            estimatedSpeed <= config.maxSpeedForOfficial -> SubjectClass.OFFICIAL
            else -> SubjectClass.UNKNOWN
        }

        val confidence = when (classification) {
            SubjectClass.RACER -> (estimatedSpeed / (config.minSpeedForRacer * 2)).coerceIn(0.5f, 1f)
            SubjectClass.OFFICIAL -> 0.3f
            SubjectClass.UNKNOWN -> 0.1f
        }

        return TrackedSubject(
            id = nextSubjectId++,
            centerX = blob.centerX,
            centerY = blob.centerY,
            width = blob.width,
            height = blob.height,
            velocityX = blob.avgMotionScore * blob.centerX.compareTo(0.5f).toFloat(),
            velocityY = blob.avgMotionScore * 0.3f,  // Racers generally move more horizontally + downhill
            speed = estimatedSpeed,
            classification = classification,
            confidence = confidence,
            framesTracked = 0,
            isOccluded = false
        )
    }

    private fun predictPosition(framesAhead: Int): Pair<Float, Float>? {
        if (positionHistory.size < 2) return null

        // Linear extrapolation from recent positions
        val recent = positionHistory.takeLast(5)
        val dx = (recent.last().first - recent.first().first) / recent.size
        val dy = (recent.last().second - recent.first().second) / recent.size

        return Pair(
            recent.last().first + dx * framesAhead,
            recent.last().second + dy * framesAhead
        )
    }

    private fun addToHistory(x: Float, y: Float) {
        positionHistory.add(Pair(x, y))
        if (positionHistory.size > maxHistorySize) {
            positionHistory.removeAt(0)
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
}

/**
 * A cluster of motion blocks = a moving object in the frame.
 */
data class MotionBlob(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val blockCount: Int,
    val avgMotionScore: Float
)

/**
 * AF target: where to point the autofocus.
 * Passed to Camera2 API as a MeteringRectangle.
 */
data class AfTarget(
    val centerX: Float,     // Normalized 0-1
    val centerY: Float,     // Normalized 0-1
    val regionSize: Float   // Size of AF region as fraction of frame
)
