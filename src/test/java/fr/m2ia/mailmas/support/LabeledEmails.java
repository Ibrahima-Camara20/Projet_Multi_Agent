package fr.m2ia.mailmas.support;

import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.support.TestSupport.MapContactDirectory;

import java.util.List;
import java.util.Map;

import static fr.m2ia.mailmas.support.TestSupport.email;

/**
 * Jeu de 21 e-mails étiquetés (agent attendu), partagé par RoutingScenariosTest (faux LLM)
 * et RoutingLlmExperimentTest (vrai modèle), pour comparer les configurations sur les mêmes données.
 */
public final class LabeledEmails {

    private LabeledEmails() {
    }

    /** Annuaire utilisé par les expériences (rôles connus de certains expéditeurs). */
    public static MapContactDirectory contacts() {
        return new MapContactDirectory()
            .add("anna.muller@partner-lab.eu", "Anna Müller", "research-partner")
            .add("paul.durand@univ.fr", "Paul Durand", "teacher")
            .add("marc.petit@univ.fr", "Marc Petit", "teacher")
            .add("lea.martin@etu.univ.fr", "Léa Martin", "student")
            .add("tom.dupont@gmail.com", "Tom Dupont", "student");
    }

    /** Jeu de test étiqueté : e-mail → agent attendu. */
    public record Labeled(Email email, String expected) {
    }

    public static List<Labeled> labeledSet() {
        return List.of(
                // étudiants
                new Labeled(email("s1", "lea.martin@etu.univ.fr", "Question sur le rendu du TP3",
                        "Le rapport doit-il être inclus dans le rendu ?"), "student"),
                new Labeled(email("s2", "hugo.leroy@etu.univ.fr", "Absence au partiel",
                        "J'étais malade le jour du partiel, y a-t-il un rattrapage ?"), "student"),
                new Labeled(email("s3", "tom.dupont@gmail.com", "Cours de mardi",
                        "Je n'ai pas pu venir au cours, où trouver les slides sur Moodle ?"), "student"),
                new Labeled(email("s4", "nina.blanc@etu.univ.fr", "Question",
                        "Bonjour, je ne comprends pas l'exercice 2 de la feuille."), "student"),
                // collègues
                new Labeled(email("t1", "paul.durand@univ.fr", "Jury du semestre 1",
                        "On cale la réunion du jury jeudi ?"), "teacher"),
                new Labeled(email("t2", "claire.roux@univ.fr", "Emploi du temps S2",
                        "Peux-tu me donner tes contraintes pour l'emploi du temps ?"), "teacher"),
                new Labeled(email("t3", "paul.durand@univ.fr", "Surveillance",
                        "Pourrais-tu assurer la surveillance de l'examen de lundi ?"), "teacher"),
                // administration
                new Labeled(email("a1", "scolarite@univ.fr", "Ordre de mission - colloque Lyon",
                        "Merci de transmettre le formulaire signé et les pièces justificatives."), "administration"),
                new Labeled(email("a2", "rh@univ.fr", "Heures complémentaires",
                        "Merci de signer l'attestation de service fait."), "administration"),
                new Labeled(email("a3", "gestion@admin.univ.fr", "Note de frais",
                        "Votre remboursement est en attente d'un justificatif."), "administration"),
                // projet SuperMAS
                new Labeled(email("r1", "anna.muller@partner-lab.eu", "SuperMAS - WP3 deliverable",
                        "Could you confirm the date for the WP3 deliverable?"), "research-supermas"),
                new Labeled(email("r2", "j.smith@partner-lab.eu", "Meeting next week",
                        "Can we discuss the work package planning?"), "research-supermas"),
                new Labeled(email("r3", "marc.petit@univ.fr", "SuperMASProject : point WP2",
                        "Peux-tu relire la section évaluation du deliverable ?"), "research-supermas"),
                // messages courts sans mot-clé : seul l'expéditeur permet de décider
                new Labeled(email("s5", "lea.martin@etu.univ.fr", "Petite question",
                        "Bonjour, est-ce que la salle a changé pour demain ?"), "student"),
                new Labeled(email("t4", "claire.roux@univ.fr", "Café ?",
                        "On se voit à 15h pour parler du module ?"), "teacher"),
                new Labeled(email("r4", "anna.muller@partner-lab.eu", "Travel arrangements",
                        "I booked the train for the meeting in Lyon."), "research-supermas"),
                // cas difficile : l'administration écrit au sujet d'un examen
                new Labeled(email("a4", "scolarite@univ.fr", "Examen de rattrapage",
                        "Merci de nous transmettre le sujet d'examen de rattrapage avant vendredi."), "administration"),
                // e-mail automatique : seules les métadonnées (liste de diffusion) indiquent le contexte
                new Labeled(new Email("m1", null, "noreply@lists-server.org", List.of("moi@univ.fr"),
                        "Weekly digest", "3 new messages in your mailing list.", null,
                        Map.of("list-id", "supermas-wp3.partner-lab.eu")), "research-supermas"),
                // aucun agent spécialisé
                new Labeled(email("g1", "contact@librairie-exemple.fr", "Votre commande",
                        "Votre commande est disponible en magasin."), "generic"),
                new Labeled(email("g2", "info@conference-ia.org", "Call for papers",
                        "Submit your paper before December 1st."), "generic"),
                // même expéditeur, autre contexte
                new Labeled(email("g3", "lea.martin@etu.univ.fr", "Vélo à vendre",
                        "Bonjour, seriez-vous intéressée par mon vélo ?"), "generic"));
    }
}
