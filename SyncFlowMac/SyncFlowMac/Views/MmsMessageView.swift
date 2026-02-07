import SwiftUI

struct MmsMessageView: View {
    let messageId: String
    let address: String
    let messageBody: String
    let subject: String?
    let timestamp: Date
    let attachments: [MmsAttachment]
    let isSent: Bool

    var body: some View {
        VStack(alignment: isSent ? .trailing : .leading, spacing: 8) {
            // Subject (if present)
            if let subject = subject, !subject.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "pin.fill")
                        .font(.caption)
                    Text(subject)
                        .font(.caption)
                        .fontWeight(.semibold)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(isSent ? Color.blue.opacity(0.2) : Color.gray.opacity(0.2))
                .cornerRadius(8)
            }

            // Message body
            if !messageBody.isEmpty {
                Text(messageBody)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(isSent ? Color.blue : Color.gray.opacity(0.2))
                    .foregroundColor(isSent ? .white : .black)
                    .cornerRadius(12)
            }

            // Attachments
            if !attachments.isEmpty {
                VStack(alignment: isSent ? .trailing : .leading, spacing: 8) {
                    ForEach(attachments) { attachment in
                        MmsAttachmentDisplayView(
                            attachment: attachment,
                            isSent: isSent
                        )
                    }
                }
            }

            // Timestamp
            Text(formatTime(timestamp))
                .font(.caption2)
                .foregroundColor(isSent ? .blue : .gray)
        }
        .frame(maxWidth: 300, alignment: isSent ? .trailing : .leading)
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

struct MmsAttachmentDisplayView: View {
    let attachment: MmsAttachment
    let isSent: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Image preview
            if attachment.isImage, let base64Data = attachment.inlineData,
               let imageData = Data(base64Encoded: base64Data),
               let nsImage = NSImage(data: imageData)
            {
                Image(nsImage: nsImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 250, maxHeight: 200)
                    .cornerRadius(8)
                    .clipped()
            }
            // Other attachments
            else {
                HStack(spacing: 12) {
                    Image(systemName: attachmentIcon())
                        .font(.body)
                        .foregroundColor(isSent ? .blue : .gray)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(attachmentName())
                            .font(.caption)
                            .fontWeight(.medium)
                            .lineLimit(1)

                        Text(attachment.contentType)
                            .font(.caption2)
                            .foregroundColor(.gray)
                    }

                    Spacer()

                    Image(systemName: "arrow.down.circle")
                        .font(.caption)
                        .foregroundColor(isSent ? .blue : .gray)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    isSent
                        ? Color.blue.opacity(0.1)
                        : Color.gray.opacity(0.1)
                )
                .border(
                    isSent ? Color.blue.opacity(0.3) : Color.gray.opacity(0.3),
                    width: 1
                )
                .cornerRadius(8)
            }
        }
    }

    private func attachmentIcon() -> String {
        if attachment.isVideo {
            return "video.fill"
        } else if attachment.isAudio {
            return "music.note"
        } else {
            return "doc.fill"
        }
    }

    private func attachmentName() -> String {
        if let fileName = attachment.fileName, !fileName.isEmpty {
            return fileName
        }

        let extensions: [String: String] = [
            "image/jpeg": "image.jpg",
            "image/png": "image.png",
            "video/mp4": "video.mp4",
            "audio/mpeg": "audio.mp3",
            "audio/wav": "audio.wav",
        ]

        return extensions[attachment.contentType]
            ?? "file_\(attachment.id.prefix(8))"
    }
}

#Preview {
    VStack(spacing: 16) {
        MmsMessageView(
            messageId: "1",
            address: "+1-234-567-8900",
            messageBody: "Check out this photo!",
            subject: "Vacation Photo",
            timestamp: Date(),
            attachments: [
                MmsAttachment(
                    id: "1",
                    contentType: "image/jpeg",
                    fileName: "photo.jpg",
                    url: nil,
                    type: "image"
                )
            ],
            isSent: true
        )

        MmsMessageView(
            messageId: "2",
            address: "+1-987-654-3210",
            messageBody: "Here's the video I mentioned",
            subject: nil,
            timestamp: Date().addingTimeInterval(-3600),
            attachments: [
                MmsAttachment(
                    id: "2",
                    contentType: "video/mp4",
                    fileName: "video.mp4",
                    url: nil,
                    type: "video"
                )
            ],
            isSent: false
        )
    }
    .padding()
}
