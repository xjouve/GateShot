package com.gateshot.coaching.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Session Report PDF Generator.
 *
 * Auto-generates an end-of-day training summary:
 * - Event name, date, discipline
 * - Number of runs per athlete
 * - Timing splits and deltas
 * - Flagged moments and coach annotations
 * - Key frames (annotated)
 * - Error patterns detected
 *
 * Exportable as PDF for parents, federation, school.
 */
class SessionReportGenerator(private val context: Context) {

    data class ReportData(
        val eventName: String,
        val date: String,
        val discipline: String,
        val coachName: String = "",
        val athletes: List<AthleteReportData>,
        val totalRuns: Int,
        val totalMedia: Int,
        val sessionNotes: String = ""
    )

    data class AthleteReportData(
        val name: String,
        val bibNumber: Int?,
        val runCount: Int,
        val bestRunTime: String = "",
        val worstRunTime: String = "",
        val averageTime: String = "",
        val flaggedMoments: Int = 0,
        val errors: List<String> = emptyList(),
        val coachNotes: String = ""
    )

    fun generatePdf(data: ReportData): File {
        val document = PdfDocument()
        val pageWidth = 595  // A4 width in points
        val pageHeight = 842 // A4 height in points

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.rgb(79, 195, 247) // GateShot primary blue
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
            isAntiAlias = true
        }
        val smallPaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            isAntiAlias = true
        }

        // Page 1: Summary
        val pageInfo1 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page1 = document.startPage(pageInfo1)
        val canvas = page1.canvas

        var y = 50f

        // Header
        canvas.drawText("GateShot Session Report", 40f, y, titlePaint)
        y += 35f
        canvas.drawText("${data.eventName} — ${data.discipline}", 40f, y, headerPaint)
        y += 22f
        canvas.drawText("Date: ${data.date}", 40f, y, bodyPaint)
        if (data.coachName.isNotEmpty()) {
            y += 18f
            canvas.drawText("Coach: ${data.coachName}", 40f, y, bodyPaint)
        }
        y += 30f

        // Summary stats
        canvas.drawText("SESSION SUMMARY", 40f, y, headerPaint)
        y += 22f
        canvas.drawText("Total runs: ${data.totalRuns}", 60f, y, bodyPaint)
        y += 18f
        canvas.drawText("Athletes: ${data.athletes.size}", 60f, y, bodyPaint)
        y += 18f
        canvas.drawText("Media captured: ${data.totalMedia}", 60f, y, bodyPaint)
        y += 30f

        // Athlete table
        canvas.drawText("ATHLETE RESULTS", 40f, y, headerPaint)
        y += 25f

        // Table header
        val colX = floatArrayOf(60f, 200f, 270f, 340f, 410f, 480f)
        canvas.drawText("Athlete", colX[0], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Bib", colX[1], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Runs", colX[2], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Best", colX[3], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Avg", colX[4], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Flags", colX[5], y, Paint(bodyPaint).apply { typeface = Typeface.DEFAULT_BOLD })
        y += 5f
        canvas.drawLine(40f, y, pageWidth - 40f, y, smallPaint)
        y += 15f

        for (athlete in data.athletes) {
            if (y > pageHeight - 80) break  // Page overflow protection

            canvas.drawText(athlete.name, colX[0], y, bodyPaint)
            canvas.drawText(athlete.bibNumber?.toString() ?: "-", colX[1], y, bodyPaint)
            canvas.drawText(athlete.runCount.toString(), colX[2], y, bodyPaint)
            canvas.drawText(athlete.bestRunTime, colX[3], y, bodyPaint)
            canvas.drawText(athlete.averageTime, colX[4], y, bodyPaint)
            canvas.drawText(athlete.flaggedMoments.toString(), colX[5], y, bodyPaint)
            y += 18f

            // Errors for this athlete
            for (error in athlete.errors.take(2)) {
                canvas.drawText("  ⚠ $error", colX[0], y, smallPaint)
                y += 14f
            }

            // Coach notes
            if (athlete.coachNotes.isNotEmpty()) {
                canvas.drawText("  → ${athlete.coachNotes}", colX[0], y, smallPaint)
                y += 14f
            }
            y += 5f
        }

        // Session notes
        if (data.sessionNotes.isNotEmpty()) {
            y += 20f
            canvas.drawText("SESSION NOTES", 40f, y, headerPaint)
            y += 20f
            // Word-wrap the notes
            val words = data.sessionNotes.split(" ")
            var line = ""
            for (word in words) {
                val testLine = if (line.isEmpty()) word else "$line $word"
                if (bodyPaint.measureText(testLine) > pageWidth - 100) {
                    canvas.drawText(line, 60f, y, bodyPaint)
                    y += 16f
                    line = word
                } else {
                    line = testLine
                }
            }
            if (line.isNotEmpty()) canvas.drawText(line, 60f, y, bodyPaint)
        }

        // Footer
        canvas.drawText("Generated by GateShot — Ski Racing Camera & Coaching App", 40f, (pageHeight - 30).toFloat(), smallPaint)

        document.finishPage(page1)

        // Save PDF
        val outputFile = File(context.cacheDir, "session_report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outputFile).use { fos ->
            document.writeTo(fos)
        }
        document.close()

        return outputFile
    }
}
