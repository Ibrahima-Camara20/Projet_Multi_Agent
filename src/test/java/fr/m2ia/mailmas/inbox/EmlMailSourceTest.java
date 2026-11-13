package fr.m2ia.mailmas.inbox;

import fr.m2ia.mailmas.model.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class EmlMailSourceTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws Exception {
        write(name, content, UTF_8);
    }

    private void write(String name, String content, Charset charset) throws Exception {
        Files.writeString(dir.resolve(name), content.replace("\n", "\r\n"), charset);
    }

    /** E-mail typique de Gmail : objet encodé, texte + HTML, quoted-printable. */
    private static final String GMAIL = """
            Message-ID: <CAF+abc123@mail.gmail.com>
            Date: Mon, 5 Oct 2026 10:30:00 +0200 (CEST)
            From: =?UTF-8?Q?L=C3=A9a_Martin?= <Lea.Martin@etu.univ.fr>
            To: Moi <moi@univ.fr>, "Dupont, Jean" <jean.dupont@univ.fr>
            Subject: =?UTF-8?Q?Question_sur_le_rendu_du_TP3_?=
             =?UTF-8?Q?=E2=80=93_urgent?=
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="000000000000b1"

            --000000000000b1
            Content-Type: text/plain; charset="UTF-8"
            Content-Transfer-Encoding: quoted-printable

            Bonjour Madame,
            le rendu du TP3 doit-il inclure le rapport ? Merci, L=C3=A9a. Une ligne tr=
            =C3=A8s longue.

            --000000000000b1
            Content-Type: text/html; charset="UTF-8"

            <div>Bonjour Madame, version HTML</div>
            --000000000000b1--
            """;

    @Test
    void readsATypicalGmailMessage() throws Exception {
        write("tp3.eml", GMAIL);
        Email e = new EmlMailSource(dir).findAll().get(0);

        assertEquals("eml-tp3", e.id());
        assertEquals("lea.martin@etu.univ.fr", e.from());            // adresse seule, en minuscules
        assertEquals(List.of("moi@univ.fr", "jean.dupont@univ.fr"), e.to());
        assertEquals("Question sur le rendu du TP3 – urgent", e.subject());
        assertEquals(Instant.parse("2026-10-05T08:30:00Z"), e.receivedAt());
        assertTrue(e.body().startsWith("Bonjour Madame,\nle rendu du TP3"));
        assertTrue(e.body().contains("Merci, Léa. Une ligne très longue."));
        assertFalse(e.body().contains("HTML"), "le texte brut est préféré au HTML");
        assertEquals("CAF+abc123@mail.gmail.com", e.threadId());       // premier message : son propre fil
    }

    @Test
    void aReplyJoinsTheThreadAndLosesTheQuotedText() throws Exception {
        write("tp3.eml", GMAIL);
        write("tp3-relance.eml", """
                Message-ID: <CAF+def456@mail.gmail.com>
                In-Reply-To: <CAF+xyz@mail.gmail.com>
                References: <CAF+abc123@mail.gmail.com> <CAF+xyz@mail.gmail.com>
                Date: Tue, 6 Oct 2026 09:00:00 +0200
                From: lea.martin@etu.univ.fr
                To: moi@univ.fr
                Subject: Re: Question sur le rendu du TP3
                Content-Type: text/plain; charset=utf-8

                Je me permets de vous relancer.

                Le lun. 5 oct. 2026 à 10:30, Léa Martin <lea.martin@etu.univ.fr> a
                écrit :
                > Bonjour Madame,
                > le rendu du TP3 doit-il inclure le rapport ?
                """);

        List<Email> emails = new EmlMailSource(dir).findAll();
        assertEquals(List.of("eml-tp3", "eml-tp3-relance"), emails.stream().map(Email::id).toList());
        Email reply = emails.get(1);
        assertEquals(emails.get(0).threadId(), reply.threadId(), "même fil que le premier message");
        assertEquals("Je me permets de vous relancer.", reply.body());
    }

    @Test
    void readsHtmlOnlyBase64AndLatin1Messages() throws Exception {
        write("html.eml", """
                From: Service scolarité <scolarite@univ.fr>
                To: moi@univ.fr
                Subject: =?ISO-8859-1?B?T3JkcmUgZGUgbWlzc2lvbg==?=
                Date: Wed, 7 Oct 2026 11:00:00 +0000
                List-Id: Scolarite M2 <m2.scolarite.univ.fr>
                Content-Type: text/html; charset=utf-8
                Content-Transfer-Encoding: base64

                PHA+TWFkYW1lLDxicj5mb3JtdWxhaXJlIHNpZ27DqSAmYW1wOyBwacOoY2VzPC9wPjxzdHlsZT5w
                e2NvbG9yOnJlZH08L3N0eWxlPg==
                """);
        write("latin1.eml", """
                From: paul.durand@univ.fr
                Subject: Réunion
                Content-Type: text/plain; charset=iso-8859-1

                Salut, on cale la réunion du jury ?
                """, Charset.forName("ISO-8859-1"));

        EmlMailSource source = new EmlMailSource(dir);
        Email html = source.findById("eml-html").orElseThrow();
        assertEquals("Ordre de mission", html.subject());
        assertEquals("Madame,\nformulaire signé & pièces", html.body());
        assertEquals("Scolarite M2 <m2.scolarite.univ.fr>", html.metadata().get("list-id"));

        Email latin1 = source.findById("eml-latin1").orElseThrow();
        assertEquals("Salut, on cale la réunion du jury ?", latin1.body());
        assertEquals("eml-latin1", latin1.threadId());                // ni Message-ID ni References
    }

    @Test
    void attachmentsAreIgnored() throws Exception {
        write("pj.eml", """
                From: anna.muller@partner-lab.eu
                Subject: Deliverable
                Content-Type: multipart/mixed; boundary=XYZ

                --XYZ
                Content-Type: text/plain

                See the attached draft.
                --XYZ
                Content-Type: text/plain; name="notes.txt"
                Content-Disposition: attachment; filename="notes.txt"

                CONTENU DE LA PIECE JOINTE
                --XYZ--
                """);
        assertEquals("See the attached draft.", new EmlMailSource(dir).findAll().get(0).body());
    }

    @Test
    void anUnreadableFileIsSkippedAndReported() throws Exception {
        write("tp3.eml", GMAIL);
        write("vide.eml", "Subject: sans expéditeur\n\ncorps");
        write("notes.txt", "pas un e-mail");

        EmlMailSource source = new EmlMailSource(dir);
        assertEquals(1, source.findAll().size());
        assertEquals(1, source.problems().size());
        assertTrue(source.problems().get(0).startsWith("vide.eml"));
    }

    @Test
    void importCopiesTheFileIntoTheInbox() throws Exception {
        write("tp3.eml", GMAIL);
        EmlMailSource target = new EmlMailSource(dir.resolve("data").resolve("inbox"));

        target.importFile(dir.resolve("tp3.eml"));
        target.importFile(dir.resolve("tp3.eml"));                    // réimport : pas de doublon
        assertEquals(1, target.findAll().size());
        assertThrows(RuntimeException.class, () -> target.importFile(dir.resolve("absent.txt")));
    }

    @Test
    void compositeMergesSourcesByDate() throws Exception {
        write("tp3.eml", GMAIL);
        MailSource demo = new MailSource() {
            public String name() { return "démo"; }
            public List<Email> findAll() {
                return List.of(new Email("001", null, "a@x.fr", null, "Ancien", "",
                        Instant.parse("2026-09-01T00:00:00Z"), null));
            }
        };
        MailSource inbox = new CompositeMailSource(List.of(demo, new EmlMailSource(dir)));

        assertEquals(List.of("001", "eml-tp3"), inbox.findAll().stream().map(Email::id).toList());
        assertTrue(inbox.findById("eml-tp3").isPresent());
        assertEquals("démo + fichiers .eml", inbox.name());
    }
}