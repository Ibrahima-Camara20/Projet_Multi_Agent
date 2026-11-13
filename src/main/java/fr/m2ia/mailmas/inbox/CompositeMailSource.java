package fr.m2ia.mailmas.inbox;

import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Réunit plusieurs sources en une seule boîte de réception (pattern Composite), triée par date.
 * Si deux sources donnent le même identifiant, la première source l'emporte.
 */
public class CompositeMailSource implements MailSource {

    private final List<MailSource> sources;

    public CompositeMailSource(List<MailSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public String name() {
        return String.join(" + ", sources.stream().map(MailSource::name).toList());
    }

    @Override
    public List<Email> findAll() {
        List<Email> all = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (MailSource source : sources) {
            for (Email e : source.findAll()) {
                if (ids.add(e.id())) {
                    all.add(e);
                }
            }
        }
        all.sort(Comparator.comparing(Email::receivedAt).thenComparing(Email::id));
        return all;
    }
}