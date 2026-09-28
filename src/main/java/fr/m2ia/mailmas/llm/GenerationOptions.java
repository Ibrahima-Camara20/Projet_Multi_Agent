package fr.m2ia.mailmas.llm;

public record GenerationOptions(double temperature, int maxTokens) {
    public static GenerationOptions defaults() { return new GenerationOptions(0.3, 800); }
}
