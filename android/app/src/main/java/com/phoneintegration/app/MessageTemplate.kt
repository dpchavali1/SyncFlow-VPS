package com.phoneintegration.app

data class MessageTemplate(
    val id: Int,
    val title: String,
    val message: String,
    val emoji: String
)

object TemplateManager {

    fun getDefaultTemplates(): List<MessageTemplate> {
        return listOf(
            MessageTemplate(1, "On My Way", "On my way! 🚗", "🚗"),
            MessageTemplate(2, "Running Late", "Running a bit late, be there soon!", "⏰"),
            MessageTemplate(3, "Call You Later", "Can't talk right now, I'll call you later! 📞", "📞"),
            MessageTemplate(4, "Thanks", "Thanks! 😊", "😊"),
            MessageTemplate(5, "Busy", "I'm busy at the moment, will get back to you soon.", "💼"),
            MessageTemplate(6, "Yes", "Yes! 👍", "👍"),
            MessageTemplate(7, "No", "Sorry, can't do that. 🙅", "🙅"),
            MessageTemplate(8, "Location", "I'm at [location]. 📍", "📍"),
            MessageTemplate(9, "Meeting", "In a meeting right now, will respond later. 🤝", "🤝"),
            MessageTemplate(10, "Good Morning", "Good morning! ☀️ Have a great day!", "☀️")
        )
    }
}