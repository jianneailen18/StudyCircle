import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * Automatic test of the SERVER using raw sockets (no GUI needed).
 * Run:  java SmokeTest        -> prints PASS/FAIL for each check, exit code 1 if anything failed.
 */
public class SmokeTest {

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }

    /** A bare-bones client that stores every line the server sends. */
    static class TC {
        final Socket sock;
        final PrintWriter out;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        TC(int port) throws IOException {
            sock = new Socket("127.0.0.1", port);
            out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                    String l;
                    while ((l = r.readLine()) != null) {
                        lines.add(l);
                    }
                } catch (IOException ignored) {
                }
                lines.add("<<CLOSED>>");
            });
            t.setDaemon(true);
            t.start();
        }

        void send(String s) {
            out.print(s + "\n");
            out.flush();
        }

        /** Consume lines until one contains the needle; null on timeout. */
        String waitFor(String needle) {
            return waitFor(needle, 3000);
        }

        String waitFor(String needle, long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            try {
                while (true) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) {
                        return null;
                    }
                    String l = lines.poll(left, TimeUnit.MILLISECONDS);
                    if (l == null) {
                        return null;
                    }
                    if (l.contains(needle)) {
                        return l;
                    }
                }
            } catch (InterruptedException e) {
                return null;
            }
        }

        /** Count (and consume) lines containing the needle that arrive in the next 400 ms. */
        int countIn400ms(String needle) {
            int n = 0;
            long end = System.currentTimeMillis() + 400;
            try {
                while (true) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) {
                        return n;
                    }
                    String l = lines.poll(left, TimeUnit.MILLISECONDS);
                    if (l == null) {
                        return n;
                    }
                    if (l.contains(needle)) {
                        n++;
                    }
                }
            } catch (InterruptedException e) {
                return n;
            }
        }

        void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** -1 = not on the removed list, 0 = removed, 1 = removed and waiting for the host's decision. */
    static int removedState(StudyServer server, String nick) {
        for (StudyServer.RemovedUser r : server.getRemovedUsers()) {
            if (r.nick.equalsIgnoreCase(nick)) {
                return r.waiting ? 1 : 0;
            }
        }
        return -1;
    }

    static boolean waitUntil(java.util.function.BooleanSupplier condition, int ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(40);
        }
        return condition.getAsBoolean();
    }

    static TC join(int port, String nick) throws IOException {
        TC c = new TC(port);
        c.send("JOIN\t" + nick);
        return c;
    }

    public static void main(String[] args) throws Exception {
        StudyServer server = new StudyServer(m -> { });
        server.start(0);
        int port = server.getPort();

        // ---- joining and nickname rules ----
        TC a = join(port, "Alice");
        check("Alice joins", a.waitFor("JOINED\tAlice") != null);

        TC bad = join(port, "   ");
        String e1 = bad.waitFor("ERROR");
        check("blank nickname rejected", e1 != null && e1.toLowerCase().contains("blank"));

        TC dup = join(port, "alice");
        String e2 = dup.waitFor("ERROR");
        check("duplicate nickname rejected (case-insensitive)", e2 != null && e2.contains("already in use"));

        TC bad2 = join(port, "bad name!");
        check("illegal characters in nickname rejected", bad2.waitFor("ERROR") != null);

        TC b = join(port, "Bob");
        boolean bobIn = b.waitFor("JOINED\tBob") != null;     // wait, so the user-list order is always Alice, Bob, Cara
        TC c = join(port, "Cara");
        check("Bob and Cara join", bobIn && c.waitFor("JOINED\tCara") != null);
        check("online user list shows 3 users to Alice", a.waitFor("USERS\tAlice\tBob\tCara") != null);
        check("3 simultaneous clients supported", true);
        check("every user gets their own name colour", a.waitFor("COLORS\tAlice\t0\tBob\t1\tCara\t2") != null);

        // ---- group chat ----
        b.send("CHAT\tHello everyone");
        String needle = "\tBob\tHello everyone";
        check("message reaches Alice", a.waitFor(needle) != null);
        check("message reaches Cara", c.waitFor(needle) != null);
        String own = b.waitFor(needle);
        check("message is echoed to the sender", own != null);
        check("message carries a time stamp (HH:mm:ss)", own != null && own.matches("MSG\t\\d\\d:\\d\\d:\\d\\d\tBob\tHello everyone\t\\d+"));
        check("sender receives it exactly once", b.countIn400ms(needle) == 0);

        a.send("CHAT\t   ");
        check("blank message rejected", a.waitFor("ERROR\tCannot send a blank message") != null);

        a.send("CHAT\t/help");
        check("/help answers with command list", a.waitFor("/name") != null);

        a.send("CHAT\t/unknown");
        check("unknown command rejected", a.waitFor("Unknown command") != null);

        // ---- edit and delete your own messages ----
        String mid = own.substring(own.lastIndexOf('\t') + 1);
        a.send("EDIT\t" + mid + "\tHacked");
        check("cannot edit someone else's message", a.waitFor("You can only edit your own") != null);
        b.send("EDIT\t" + mid + "\tHello everyone (fixed)");
        check("owner edits own message, everyone sees it", a.waitFor("EDITED\t" + mid + "\tHello everyone (fixed)") != null);
        server.setEditWindowMillis(1);
        Thread.sleep(30);
        b.send("EDIT\t" + mid + "\tToo late");
        check("edit refused after the time limit", b.waitFor("within 5 minutes") != null);
        server.setEditWindowMillis(Protocol.EDIT_WINDOW_MS);
        a.send("DELETE\t" + mid);
        check("cannot delete someone else's message", a.waitFor("You can only delete your own") != null);
        b.send("DELETE\t" + mid);
        check("owner deletes own message, everyone sees it", a.waitFor("DELETED\t" + mid) != null);
        b.send("DELETE\t" + mid);
        check("deleting twice is refused", b.waitFor("already deleted") != null);
        b.send("EDIT\t" + mid + "\tzombie");
        check("deleted message cannot be edited", b.waitFor("was deleted") != null);

        // ---- reply to a chat message ----
        b.send("REPLYMSG\t" + mid + "\tanswering a deleted one");
        check("cannot reply to a deleted message", b.waitFor("was deleted") != null);
        a.send("CHAT\tWho knows sockets?");
        String qLine = a.waitFor("\tAlice\tWho knows sockets?");
        String quid = qLine.substring(qLine.lastIndexOf('\t') + 1);
        b.send("REPLYMSG\t" + quid + "\tI do");
        String rl = c.waitFor("\tBob\tI do\t");
        check("reply reaches everyone", rl != null);
        check("reply carries the original sender and text", rl != null && rl.endsWith("\tAlice\tWho knows sockets?"));
        b.send("REPLYMSG\t9999\tghost");
        check("reply to an unknown message refused", b.waitFor("can no longer be replied to") != null);

        // ---- rename ----
        c.send("CHAT\t/name Carla");
        check("/name confirms to requester", c.waitFor("NICK\tCarla") != null);
        check("/name updates everyone's user list", a.waitFor("USERS\tAlice\tBob\tCarla") != null);
        check("a renamed user keeps the same colour", a.waitFor("COLORS\tAlice\t0\tBob\t1\tCarla\t2") != null);
        c.send("CHAT\t/name bob");
        check("rename to a taken nickname rejected", c.waitFor("already in use") != null);
        c.send("CHAT\t/name");
        check("rename with blank nickname rejected", c.waitFor("ERROR\tNickname cannot be blank") != null);

        // ---- feature 1: question queue ----
        a.send("ASK\tWhat is a ServerSocket?");
        String q1 = b.waitFor("QUEUE\t1\t1\t0\t0\t0\tAlice\tWhat is a ServerSocket?");
        check("question appears for other users", q1 != null);
        b.send("UPVOTE\t1");
        check("upvote counted and synchronised", a.waitFor("QUEUE\t1\t1\t1\t0\t0\tAlice") != null);
        c.send("UPVOTE\t1");
        check("second upvote counted", a.waitFor("QUEUE\t1\t1\t2\t0\t0\tAlice") != null);
        b.send("RESOLVE\t1");
        check("only the asker can resolve", b.waitFor("Only Alice can mark") != null);
        a.send("RESOLVE\t1");
        check("asker resolves question for everyone", c.waitFor("QUEUE\t1\t1\t2\t1\t") != null);

        b.send("UNRESOLVE\t1");
        check("only the asker can reopen a question", b.waitFor("Only Alice can reopen") != null);
        a.send("UNRESOLVE\t1");
        check("asker reopens (un-resolves) the question for everyone", c.waitFor("QUEUE\t1\t1\t2\t0\t") != null);
        a.send("UNRESOLVE\t1");
        check("un-resolving twice keeps it open (not toggled back)", c.countIn400ms("QUEUE\t1\t1\t2\t1\t") == 0);

        b.send("QEDIT\t1\thacked");
        check("only the asker can edit a question", b.waitFor("You can only edit your own question") != null);
        a.send("QEDIT\t1\tWhat is a ServerSocket in Java?");
        check("asker edits question, everyone sees it", c.waitFor("What is a ServerSocket in Java?") != null);
        b.send("QREPLY\t1\tIt listens for connections");
        check("anyone can reply, everyone sees the reply", a.waitFor("\tIt listens for connections") != null);
        a.send("REDIT\t1\thacked");
        check("only the author can edit a reply", a.waitFor("You can only edit your own repl") != null);
        b.send("REDIT\t1\tIt waits for client connections");
        check("author edits own reply, everyone sees it", a.waitFor("\tIt waits for client connections") != null);
        a.send("RDELETE\t1");
        check("only the author can delete a reply", a.waitFor("You can only delete your own repl") != null);
        b.send("RDELETE\t1");
        check("author deletes own reply for everyone", a.waitFor("in Java?\t0") != null);
        b.send("QREPLY\t99\tnobody home");
        check("reply to a missing question refused", b.waitFor("no longer exists") != null);
        b.send("QREPLY\t1\tThis dies with the question");
        check("reply added again", a.waitFor("\tThis dies with the question") != null);
        b.send("QDELETE\t1");
        check("only the asker can delete a question", b.waitFor("You can only delete your own question") != null);
        a.send("QDELETE\t1");
        check("asker deletes question for everyone", c.waitFor("QUEUE\t0") != null);

        // ---- feature 2: live poll ----
        a.send("POLLSTART\tBest topic?\tSockets\tThreads\tSwing");
        check("poll appears for everyone", b.waitFor("POLL\t1\tAlice\t0\tBest topic?\t-1\t3\tSockets\t0\tThreads\t0\tSwing\t0") != null);
        b.send("POLLSTART\tAnother?\tYes\tNo");
        check("second open poll refused", b.waitFor("already open") != null);
        b.send("VOTE\t1\t0");
        c.send("VOTE\t1\t0");
        a.send("VOTE\t1\t1");
        check("live counts synchronised (2/1/0)", a.waitFor("\t3\tSockets\t2\tThreads\t1\tSwing\t0") != null);
        a.send("VOTE\t1\t2");
        check("changing a vote moves it (2/0/1)", c.waitFor("\t3\tSockets\t2\tThreads\t0\tSwing\t1") != null);
        b.send("POLLEND\t1");
        check("only creator can close the poll", b.waitFor("Only Alice can close") != null);
        a.send("POLLEND\t1");
        check("creator closes poll", c.waitFor("POLL\t1\tAlice\t1\t") != null);
        b.send("VOTE\t1\t0");
        check("voting on closed poll rejected", b.waitFor("closed") != null);
        a.send("POLLSTART\tOnly one\tx");
        check("poll with fewer than 2 options rejected", a.waitFor("2 to 5 options") != null);

        // ---- private messages ----
        a.send("PM\tBob\tsecret for Bob");
        String pmB = b.waitFor("PMSG\t");
        check("private message reaches the receiver", pmB != null && pmB.endsWith("\tAlice\tBob\tsecret for Bob"));
        check("sender also sees their private message", a.waitFor("\tAlice\tBob\tsecret for Bob") != null);
        check("others never see a private message", c.countIn400ms("secret for Bob") == 0);
        a.send("CHAT\t/msg bob via command");
        check("/msg works like the Private message button", b.waitFor("\tAlice\tBob\tvia command") != null);
        a.send("PM\tNobody\thi");
        check("private message to an unknown user refused", a.waitFor("No online user named") != null);
        a.send("PM\tAlice\thi me");
        check("private message to yourself refused", a.waitFor("cannot send a private message to yourself") != null);

        // ---- host announcement ----
        check("blank announcement refused", server.announce("   ") != null);
        check("announcement sent", server.announce("Quiz at 3 PM") == null);
        check("everyone receives the announcement", a.waitFor("ANNOUNCE\t") != null && c.waitFor("Quiz at 3 PM") != null);

        // ---- study timer ----
        check("timer must be at least 1 and at most 30 days", server.startTimer(0) != null
                && server.startTimer(43201) != null
                && server.startTimer(721, StudyServer.TimerUnit.HOURS) != null
                && server.startTimer(31, StudyServer.TimerUnit.DAYS) != null);
        check("timer starts", server.startTimer(25) == null);
        check("everyone sees the timer start (25 min = 1500 s)", a.waitFor("TIMER\t1500\trunning") != null && c.waitFor("TIMER\t1500\trunning") != null);
        TC late = join(port, "Late");
        check("someone joining later is told how long is left", late.waitFor("TIMER\t") != null);
        check("timer in HOURS: 2 hours = 7200 s", server.startTimer(2, StudyServer.TimerUnit.HOURS) == null && a.waitFor("TIMER\t7200\trunning") != null);
        check("timer in DAYS: 1 day = 86400 s", server.startTimer(1, StudyServer.TimerUnit.DAYS) == null && a.waitFor("TIMER\t86400\trunning") != null);
        check("the longest timer (30 days) is accepted", server.startTimer(30, StudyServer.TimerUnit.DAYS) == null && a.waitFor("TIMER\t2592000\trunning") != null);
        check("system message names the unit", a.waitFor("30 days") != null);
        check("the time left is written clearly (minutes / hours / days)",
                Protocol.formatDuration(59).equals("00:59") && Protocol.formatDuration(299).equals("04:59")
                && Protocol.formatDuration(3600).equals("1:00:00") && Protocol.formatDuration(7509).equals("2:05:09")
                && Protocol.formatDuration(86400).equals("1d 00:00:00") && Protocol.formatDuration(93909).equals("1d 02:05:09")
                && Protocol.formatDuration(0).equals("00:00"));
        server.stopTimer();
        check("host can stop the timer", a.waitFor("TIMER\t0\tstopped") != null);
        check("timer starts again (short, for the test)", server.startTimerMillis(300, "test") == null);
        check("timer finishes by itself", a.waitFor("TIMER\t0\tfinished") != null);
        late.close();
        check("Late left", a.waitFor("Late left the chat") != null);

        // ---- host removes a user ----
        TC kay = join(port, "Kay");
        check("Kay joins", kay.waitFor("JOINED\tKay") != null);
        check("kick unknown user returns false", !server.kickUser("Nobody"));
        check("kick returns true", server.kickUser("kay"));
        check("kicked user is told", kay.waitFor("KICKED") != null);
        check("kicked user is disconnected", kay.waitFor("<<CLOSED>>") != null);
        check("others are told Kay was removed", a.waitFor("Kay was removed by the host") != null);

        // ---- a removed user needs the host's permission to come back ----
        check("the host sees Kay in the removed list (not waiting yet)", removedState(server, "Kay") == 0);
        TC again = join(port, "kay");   // tries again (nickname is not case-sensitive)
        check("removed user is NOT let in: told to wait for the host", again.waitFor("WAIT\t") != null);
        check("removed user did not get into the chat", again.countIn400ms("JOINED") == 0);
        check("the host sees that Kay is waiting", removedState(server, "Kay") == 1);
        check("a waiting user is not in the online list", !server.getUserNames().contains("kay") && !server.getUserNames().contains("Kay"));
        check("host refuses the request", server.denyUser("Kay"));
        check("refused user is told", again.waitFor("DENIED") != null);
        check("refused user is disconnected", again.waitFor("<<CLOSED>>") != null);
        check("refused user is still on the removed list", removedState(server, "Kay") == 0);

        TC third = join(port, "Kay");
        check("asks again: waits again", third.waitFor("WAIT\t") != null);
        check("host allows the waiting user", server.allowUser("Kay"));
        check("allowed user is told", third.waitFor("ALLOWED") != null);
        third.send("JOIN\tKay");
        check("allowed user joins normally", third.waitFor("JOINED\tKay") != null);
        check("Kay is no longer on the removed list", removedState(server, "Kay") == -1);
        third.close();
        check("Kay left again", a.waitFor("Kay left the chat") != null);

        TC giveUp = join(port, "Zed");
        check("Zed joins", giveUp.waitFor("JOINED\tZed") != null);
        check("host removes Zed", server.kickUser("Zed"));
        check("Zed is disconnected", giveUp.waitFor("<<CLOSED>>") != null);
        TC zed2 = join(port, "Zed");
        check("Zed asks to come back", zed2.waitFor("WAIT\t") != null);
        zed2.close();   // Cancel
        boolean stopped = false;
        for (int i = 0; i < 20 && !stopped; i++) {
            Thread.sleep(100);
            stopped = removedState(server, "Zed") == 0;
        }
        check("if the user cancels, they are no longer 'waiting'", stopped);
        check("host can allow a removed user BEFORE they ask", server.allowUser("Zed"));
        TC zed3 = join(port, "Zed");
        check("then the user joins right away", zed3.waitFor("JOINED\tZed") != null);
        check("allow/deny of an unknown name returns false", !server.allowUser("Nobody") && !server.denyUser("Nobody"));
        zed3.close();
        check("Zed left", a.waitFor("Zed left the chat") != null);

        // ---- quiz ----
        List<String> four = Arrays.asList("Paris", "Rome", "Madrid", "");
        check("quiz: the question is required", server.startQuiz("  ", four, 0, 30) != null);
        check("quiz: needs at least 2 choices", server.startQuiz("Q?", Arrays.asList("only one", "", "", ""), 0, 30) != null);
        check("quiz: at most 4 choices", server.startQuiz("Q?", Arrays.asList("a", "b", "c", "d", "e"), 0, 30) != null);
        check("quiz: the correct answer cannot be an empty choice", server.startQuiz("Q?", four, 3, 30) != null);
        check("quiz: time must be 5 to 300 seconds", server.startQuiz("Q?", four, 0, 4) != null && server.startQuiz("Q?", four, 0, 301) != null);
        check("quiz question starts", server.startQuiz("Capital of France?", four, 0, 30) == null);
        String ql = a.waitFor("QUIZ\t");
        check("everyone gets the question and its choices", ql != null
                && ql.endsWith("\tCapital of France?\t3\tParis\tRome\tMadrid")
                && b.waitFor("QUIZ\t") != null && c.waitFor("QUIZ\t") != null);
        check("only one question at a time", server.startQuiz("Another?", four, 0, 30) != null);
        a.send("QZANSWER\t1\t0");
        check("an answer is accepted", a.waitFor("QZACK\t1\t0") != null);
        a.send("QZANSWER\t1\t1");
        check("only one answer per person", a.waitFor("already answered") != null);
        b.send("QZANSWER\t1\t7");
        check("an answer that is not a choice is refused", b.waitFor("Pick one of the answer choices") != null);
        b.send("QZANSWER\t1\t1");
        c.send("QZANSWER\t1\t0");
        check("the host sees how many answered", waitUntil(() -> server.quizStatus().contains("3 answered"), 2000));
        server.endQuiz();
        check("result: correct answer and how many chose each", a.waitFor("QZRESULT\t1\t0\t3\t2\t1\t0") != null
                && c.waitFor("QZRESULT\t1\t0\t3\t2\t1\t0") != null);
        check("leaderboard: most correct first", a.waitFor("QZBOARD\t3\tAlice\t1\t1\tCarla\t1\t1\tBob\t0\t1") != null);
        check("host sees the same leaderboard", server.getLeaderboardLines().size() == 3
                && server.getLeaderboardLines().get(0).startsWith("1. Alice"));
        a.send("QZANSWER\t1\t0");
        check("answering after the question closed is refused", a.waitFor("closed") != null);
        check("no question open now", !server.isQuizOpen());

        check("quiz 2 starts (nobody will answer)", server.startQuiz("Quick one?", Arrays.asList("Yes", "No"), 1, 5) == null);
        check("a question closes by itself when the time is up", a.waitFor("QZRESULT\t2\t1\t2\t0\t0", 8000) != null);

        check("quiz 3 starts", server.startQuiz("Late question?", four, 0, 30) == null);
        TC newcomer = join(port, "Late");
        check("a newcomer gets the leaderboard so far", newcomer.waitFor("QZBOARD\t3\tAlice") != null);
        check("a newcomer can still answer the open question", newcomer.waitFor("QUIZ\t3\t") != null);
        newcomer.send("QZANSWER\t3\t0");
        check("the newcomer's answer is accepted", newcomer.waitFor("QZACK\t3\t0") != null);
        server.endQuiz();
        check("quiz 3 result (1 chose Paris)", a.waitFor("QZRESULT\t3\t0\t3\t1\t0\t0") != null);
        newcomer.close();
        check("Late left", a.waitFor("Late left the chat") != null);

        b.send("CHAT\t/name Bobby");
        check("a renamed user keeps their quiz score", a.waitFor("\tBobby\t0\t1") != null);
        b.send("CHAT\t/name Bob");
        check("renamed back", a.waitFor("Bobby is now known as Bob") != null);
        server.resetQuizScores();
        check("the host can reset the leaderboard", a.waitFor("QZBOARD\t0") != null && server.getLeaderboardLines().isEmpty());

        // ---- one client disconnecting must not stop the others ----
        b.close();   // abrupt disconnect (like closing the window)
        check("other users are told when someone drops", a.waitFor("Bob left the chat") != null);
        check("user list shrinks to 2", c.waitFor("USERS\tAlice\tCarla") != null);
        a.send("CHAT\tstill here");
        check("server and other clients keep working", c.waitFor("\tAlice\tstill here") != null);

        // ---- /exit ----
        c.send("CHAT\t/exit");
        check("/exit is acknowledged with BYE", c.waitFor("BYE") != null);
        check("/exit closes that connection", c.waitFor("<<CLOSED>>") != null);
        check("others see the user leave", a.waitFor("Carla left the chat") != null);

        // ---- many clients at once ----
        int n = 12;
        TC[] many = new TC[n];
        for (int i = 0; i < n; i++) {
            many[i] = join(port, "U" + i);
        }
        boolean allJoined = true;
        for (int i = 0; i < n; i++) {
            allJoined &= many[i].waitFor("JOINED\tU" + i) != null;
        }
        check(n + " more clients can join concurrently", allJoined);
        many[0].send("CHAT\tbroadcast test");
        boolean allGot = true;
        for (int i = 0; i < n; i++) {
            allGot &= many[i].waitFor("\tU0\tbroadcast test") != null;
        }
        check("broadcast reaches all " + n + " clients", allGot);

        // ---- server shutdown ----
        server.stop();
        check("clients are disconnected when the server stops", a.waitFor("<<CLOSED>>") != null);

        System.out.println();
        System.out.println("Result: " + passed + " passed, " + failed + " failed.");
        System.exit(failed == 0 ? 0 : 1);
    }
}
