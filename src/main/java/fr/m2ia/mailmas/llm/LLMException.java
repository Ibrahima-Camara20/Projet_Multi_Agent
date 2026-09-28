package fr.m2ia.mailmas.llm;

/** Moteur injoignable, délai dépassé, réponse invalide... */
public class LLMException extends Exception {
    public LLMException(String message) { super(message); }
    public LLMException(String message, Throwable cause) { super(message, cause); }
}
