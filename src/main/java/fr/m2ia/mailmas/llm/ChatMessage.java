package fr.m2ia.mailmas.llm;

/** Message au format "chat", compris par tous les moteurs locaux courants. */
public record ChatMessage(Role role, String content) {
    public enum Role { SYSTEM, USER, ASSISTANT }

    public static ChatMessage system(String c) { return new ChatMessage(Role.SYSTEM, c); }
    public static ChatMessage user(String c) { return new ChatMessage(Role.USER, c); }
    public static ChatMessage assistant(String c) { return new ChatMessage(Role.ASSISTANT, c); }
}
