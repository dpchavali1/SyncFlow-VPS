//
//  ExportService.swift
//  SyncFlowMac
//
//  Service for exporting conversations to PDF, plain text, and CSV formats
//

import Foundation
import AppKit
import PDFKit

class ExportService {
    static let shared = ExportService()

    private init() {}

    // MARK: - Export Formats

    enum ExportFormat: String, CaseIterable, Identifiable {
        case pdf = "PDF"
        case plainText = "Text"
        case csv = "CSV"

        var id: String { rawValue }

        var fileExtension: String {
            switch self {
            case .pdf: return "pdf"
            case .plainText: return "txt"
            case .csv: return "csv"
            }
        }

        var contentType: String {
            switch self {
            case .pdf: return "application/pdf"
            case .plainText: return "text/plain"
            case .csv: return "text/csv"
            }
        }
    }

    // MARK: - Export Options

    struct ExportOptions {
        let format: ExportFormat
        let includeTimestamps: Bool
        let includeAttachmentInfo: Bool
        let dateRange: ClosedRange<Date>?

        init(
            format: ExportFormat,
            includeTimestamps: Bool = true,
            includeAttachmentInfo: Bool = true,
            dateRange: ClosedRange<Date>? = nil
        ) {
            self.format = format
            self.includeTimestamps = includeTimestamps
            self.includeAttachmentInfo = includeAttachmentInfo
            self.dateRange = dateRange
        }
    }

    // MARK: - Export Methods

    func exportConversation(
        conversation: Conversation,
        messages: [Message],
        options: ExportOptions
    ) async throws -> URL {
        // Filter messages by date range if specified
        var filteredMessages = messages
        if let dateRange = options.dateRange {
            filteredMessages = messages.filter { msg in
                let msgDate = Date(timeIntervalSince1970: msg.date / 1000)
                return dateRange.contains(msgDate)
            }
        }

        // Sort messages by date (oldest first for export)
        filteredMessages = filteredMessages.sorted { $0.date < $1.date }

        switch options.format {
        case .pdf:
            return try await generatePDF(conversation: conversation, messages: filteredMessages, options: options)
        case .plainText:
            return try await generatePlainText(conversation: conversation, messages: filteredMessages, options: options)
        case .csv:
            return try await generateCSV(conversation: conversation, messages: filteredMessages, options: options)
        }
    }

    // MARK: - PDF Generation

    private func generatePDF(
        conversation: Conversation,
        messages: [Message],
        options: ExportOptions
    ) async throws -> URL {
        let pdfMetaData = [
            kCGPDFContextCreator: "SyncFlow",
            kCGPDFContextTitle: "Conversation with \(conversation.displayName)"
        ]

        let format = NSMutableParagraphStyle()
        format.alignment = .left
        format.lineBreakMode = .byWordWrapping

        let pageWidth: CGFloat = 612  // Letter width in points
        let pageHeight: CGFloat = 792  // Letter height in points
        let margin: CGFloat = 50

        let pdfData = NSMutableData()

        guard let consumer = CGDataConsumer(data: pdfData as CFMutableData),
              let pdfContext = CGContext(consumer: consumer, mediaBox: nil, pdfMetaData as CFDictionary) else {
            throw ExportError.pdfCreationFailed
        }

        var currentY: CGFloat = pageHeight - margin
        let contentWidth = pageWidth - (2 * margin)

        // Helper to start new page
        func startNewPage() {
            if currentY < pageHeight - margin {
                pdfContext.endPDFPage()
            }
            var mediaBox = CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)
            let pageInfo: [CFString: Any] = [
                kCGPDFContextMediaBox: NSValue(rect: mediaBox)
            ]
            pdfContext.beginPDFPage(pageInfo as CFDictionary)
            currentY = pageHeight - margin
        }

        // Helper to draw text
        func drawText(_ text: String, font: NSFont, color: NSColor, maxWidth: CGFloat) -> CGFloat {
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: color,
                .paragraphStyle: format
            ]

            let attributedString = NSAttributedString(string: text, attributes: attributes)
            let framesetter = CTFramesetterCreateWithAttributedString(attributedString)

            let suggestedSize = CTFramesetterSuggestFrameSizeWithConstraints(
                framesetter,
                CFRangeMake(0, attributedString.length),
                nil,
                CGSize(width: maxWidth, height: CGFloat.greatestFiniteMagnitude),
                nil
            )

            let textHeight = ceil(suggestedSize.height)

            // Check if we need a new page
            if currentY - textHeight < margin {
                startNewPage()
            }

            let path = CGPath(rect: CGRect(x: margin, y: currentY - textHeight, width: maxWidth, height: textHeight), transform: nil)
            let frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(0, attributedString.length), path, nil)

            pdfContext.saveGState()
            CTFrameDraw(frame, pdfContext)
            pdfContext.restoreGState()

            return textHeight
        }

        // Start first page
        startNewPage()

        // Draw header
        let titleFont = NSFont.boldSystemFont(ofSize: 18)
        let subtitleFont = NSFont.systemFont(ofSize: 12)
        let bodyFont = NSFont.systemFont(ofSize: 11)
        let timestampFont = NSFont.systemFont(ofSize: 9)

        // Title
        currentY -= drawText("CONVERSATION EXPORT", font: titleFont, color: .black, maxWidth: contentWidth)
        currentY -= 8

        // Divider line
        pdfContext.setStrokeColor(NSColor.gray.cgColor)
        pdfContext.setLineWidth(1)
        pdfContext.move(to: CGPoint(x: margin, y: currentY))
        pdfContext.addLine(to: CGPoint(x: pageWidth - margin, y: currentY))
        pdfContext.strokePath()
        currentY -= 16

        // Contact info
        currentY -= drawText("Contact: \(conversation.displayName) (\(conversation.address))", font: subtitleFont, color: .darkGray, maxWidth: contentWidth)
        currentY -= 4

        let exportDateFormatter = DateFormatter()
        exportDateFormatter.dateStyle = .long
        exportDateFormatter.timeStyle = .short
        let exportDate = exportDateFormatter.string(from: Date())
        currentY -= drawText("Exported: \(exportDate)", font: subtitleFont, color: .darkGray, maxWidth: contentWidth)
        currentY -= 4

        currentY -= drawText("Messages: \(messages.count)", font: subtitleFont, color: .darkGray, maxWidth: contentWidth)
        currentY -= 8

        // Divider line
        pdfContext.move(to: CGPoint(x: margin, y: currentY))
        pdfContext.addLine(to: CGPoint(x: pageWidth - margin, y: currentY))
        pdfContext.strokePath()
        currentY -= 24

        // Messages
        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .medium
        dateFormatter.timeStyle = .short

        for message in messages {
            // Timestamp header
            if options.includeTimestamps {
                let timestamp = Date(timeIntervalSince1970: message.date / 1000)
                let timestampString = "[\(dateFormatter.string(from: timestamp))]"
                currentY -= drawText(timestampString, font: timestampFont, color: .gray, maxWidth: contentWidth)
                currentY -= 2
            }

            // Sender
            let senderName = message.isReceived ? (message.contactName ?? message.address) : "You"
            currentY -= drawText("\(senderName):", font: NSFont.boldSystemFont(ofSize: 11), color: message.isReceived ? .blue : .darkGray, maxWidth: contentWidth)
            currentY -= 2

            // Attachment info
            if options.includeAttachmentInfo && message.hasAttachments {
                if let attachments = message.attachments {
                    for attachment in attachments {
                        let attachmentInfo = "[Attachment: \(attachment.fileName ?? attachment.type)]"
                        currentY -= drawText(attachmentInfo, font: NSFont.systemFont(ofSize: 10), color: .gray, maxWidth: contentWidth)
                        currentY -= 2
                    }
                }
            }

            // Message body
            if !message.body.isEmpty {
                currentY -= drawText(message.body, font: bodyFont, color: .black, maxWidth: contentWidth)
            }

            currentY -= 16  // Spacing between messages
        }

        // End PDF
        pdfContext.endPDFPage()
        pdfContext.closePDF()

        // Save to temp file
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = sanitizeFileName("\(conversation.displayName)_export")
        let fileURL = tempDir.appendingPathComponent("\(fileName).pdf")

        try pdfData.write(to: fileURL, options: .atomic)

        return fileURL
    }

    // MARK: - Plain Text Generation

    private func generatePlainText(
        conversation: Conversation,
        messages: [Message],
        options: ExportOptions
    ) async throws -> URL {
        var content = ""

        // Header
        content += "Conversation with \(conversation.displayName) (\(conversation.address))\n"

        let exportDateFormatter = DateFormatter()
        exportDateFormatter.dateStyle = .long
        exportDateFormatter.timeStyle = .short
        content += "Exported: \(exportDateFormatter.string(from: Date()))\n"
        content += "\n---\n\n"

        // Messages
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd h:mm a"

        for message in messages {
            let senderName = message.isReceived ? (message.contactName ?? message.address) : "You"

            if options.includeTimestamps {
                let timestamp = Date(timeIntervalSince1970: message.date / 1000)
                content += "\(dateFormatter.string(from: timestamp)) - \(senderName):\n"
            } else {
                content += "\(senderName):\n"
            }

            // Attachment info
            if options.includeAttachmentInfo && message.hasAttachments {
                if let attachments = message.attachments {
                    for attachment in attachments {
                        content += "[Attachment: \(attachment.fileName ?? attachment.type)]\n"
                    }
                }
            }

            content += "\(message.body)\n\n"
        }

        // Save to temp file
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = sanitizeFileName("\(conversation.displayName)_export")
        let fileURL = tempDir.appendingPathComponent("\(fileName).txt")

        try content.write(to: fileURL, atomically: true, encoding: .utf8)

        return fileURL
    }

    // MARK: - CSV Generation

    private func generateCSV(
        conversation: Conversation,
        messages: [Message],
        options: ExportOptions
    ) async throws -> URL {
        var rows: [[String]] = []

        // Header row
        var headers = ["Date", "Time", "Sender", "Message"]
        if options.includeAttachmentInfo {
            headers.append(contentsOf: ["HasAttachment", "AttachmentType"])
        }
        rows.append(headers)

        // Data rows
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"

        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "h:mm a"

        for message in messages {
            let timestamp = Date(timeIntervalSince1970: message.date / 1000)
            let senderName = message.isReceived ? (message.contactName ?? message.address) : "You"

            var row = [
                dateFormatter.string(from: timestamp),
                timeFormatter.string(from: timestamp),
                senderName,
                message.body
            ]

            if options.includeAttachmentInfo {
                row.append(message.hasAttachments ? "true" : "false")

                if message.hasAttachments, let attachments = message.attachments {
                    let types = attachments.map { $0.type }.joined(separator: "; ")
                    row.append(types)
                } else {
                    row.append("")
                }
            }

            rows.append(row)
        }

        // Generate CSV content
        let csvContent = rows.map { row in
            row.map { escapeCSVField($0) }.joined(separator: ",")
        }.joined(separator: "\n")

        // Save to temp file
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = sanitizeFileName("\(conversation.displayName)_export")
        let fileURL = tempDir.appendingPathComponent("\(fileName).csv")

        try csvContent.write(to: fileURL, atomically: true, encoding: .utf8)

        return fileURL
    }

    // MARK: - Helper Methods

    private func escapeCSVField(_ field: String) -> String {
        // If field contains comma, quote, or newline, wrap in quotes and escape existing quotes
        let needsQuoting = field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")

        if needsQuoting {
            let escaped = field.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(escaped)\""
        }

        return field
    }

    private func sanitizeFileName(_ name: String) -> String {
        // Remove or replace characters that aren't safe for filenames
        let invalidCharacters = CharacterSet(charactersIn: "/\\:*?\"<>|")
        return name.components(separatedBy: invalidCharacters).joined(separator: "_")
    }

    // MARK: - Errors

    enum ExportError: Error, LocalizedError {
        case pdfCreationFailed
        case writeFailure(Error)
        case noMessages

        var errorDescription: String? {
            switch self {
            case .pdfCreationFailed:
                return "Failed to create PDF document"
            case .writeFailure(let error):
                return "Failed to write file: \(error.localizedDescription)"
            case .noMessages:
                return "No messages to export"
            }
        }
    }
}
