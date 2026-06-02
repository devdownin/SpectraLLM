package fr.spectra.dto;

/**
 * Message dans l'historique conversationnel (Conversational RAG).
 *
 * @param role    "user" ou "assistant"
 * @param content contenu textuel du message
 */
public record ConversationMessage(String role, String content) {}
