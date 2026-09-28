package fr.m2ia.mailmas.llm;

/** Tokens à -1 si le moteur ne les renvoie pas. */
public record LLMResponse(String text, String model, int promptTokens, int completionTokens) {}
