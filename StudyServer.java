import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Study Circle server.
 *
 * - One thread per client (cached thread pool) so clients never block each other.
 * - All shared state (users, question queue, poll) is guarded by one lock.
 * - Network writes are done OUTSIDE the lock so a slow client cannot freeze the others.
 *
 * Run with a window:      java StudyServer
 * Run without a window:   java StudyServer --nogui 5000
 */
public class StudyServer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_QUESTIONS = 100;
    private static final int MAX_STORED_MESSAGES = 1000;   // oldest messages can no longer be edited/deleted

    // ------------------------------------------------------------------ data

    /** One quiz question: who picked what, until it closes. */
    private static class QuizState {
        final int id;
        final String question;
        final String[] options;
        final int correct;
        final long endsAt;
        boolean open = true;
        final Map<Integer, Integer> choices = new LinkedHashMap<>();   // client id -> option picked
        final Map<Integer, String> names = new HashMap<>();            // client id -> nickname when answering
        java.util.concurrent.ScheduledFuture<?> task;

        QuizState(int id, String question, String[] options, int correct, long endsAt) {
            this.id = id;
            this.question = question;
            this.options = options;
            this.correct = correct;
            this.endsAt = endsAt;
        }
    }

    private static class Score {
        String name;
        int correct;
        int answered;

        Score(String name) {
            this.name = name;
        }
    }

    private static class Reply {
        final int id;
        final int authorId;
        String authorNick;
        final String time;
        String text;

        Reply(int id, int authorId, String authorNick, String time, String text) {
            this.id = id;
            this.authorId = authorId;
            this.authorNick = authorNick;
            this.time = time;
            this.text = text;
        }
    }

    private static class Question {
        final int id;
        final int askerId;
        String askerNick;
        String text;   // not final: the asker may edit it
        final Set<Integer> upvoters = new HashSet<>();
        final List<Reply> replies = new ArrayList<>();
        boolean resolved;

        Question(int id, int askerId, String askerNick, String text) {
            this.id = id;
            this.askerId = askerId;
            this.askerNick = askerNick;
            this.text = text;
        }
    }

    /** A chat message the server remembers, so its owner can edit or delete it later. */
    private static class ChatMsg {
        final int id;
        final int ownerId;
        final long createdAt;
        final String senderNick;   // nickname when it was sent (used for "replying to ...")
        String text;
        boolean deleted;

        ChatMsg(int id, int ownerId, String senderNick, String text, long createdAt) {
            this.id = id;
            this.ownerId = ownerId;
            this.senderNick = senderNick;
            this.text = text;
            this.createdAt = createdAt;
        }
    }

    private static class Poll {
        final int id;
        final int creatorId;
        String creatorNick;
        final String question;
        final List<String> options;
        final Map<Integer, Integer> votes = new HashMap<>(); // clientId -> option index
        boolean closed;

        Poll(int id, int creatorId, String creatorNick, String question, List<String> options) {
            this.id = id;
            this.creatorId = creatorId;
            this.creatorNick = creatorNick;
            this.question = question;
            this.options = options;
        }
    }

    // ----------------------------------------------------------------- state

    private final Object lock = new Object();
    private final Map<Integer, Handler> clients = new LinkedHashMap<>();   // joined users, by id
    private final Set<Handler> allHandlers = ConcurrentHashMap.newKeySet(); // every open socket
    private final List<Question> questions = new ArrayList<>();
    private final Map<Integer, ChatMsg> messages = new LinkedHashMap<>();   // guarded by lock
    private volatile long editWindowMillis = Protocol.EDIT_WINDOW_MS;
    private int nextMsgId = 1;
    private Poll poll;
    private int nextClientId = 1;
    private int nextQuestionId = 1;
    private int nextReplyId = 1;
    private int nextPollId = 1;
    private int nextColor;
    private QuizState quiz;                     // the current (or last) quiz question; guarded by lock
    private int nextQuizId = 1;
    private final Map<String, Score> scores = new LinkedHashMap<>();   // lower-case nickname -> quiz score; guarded by lock
    private static final int MAX_WAITING = 20;
    private final Map<String, String> blocked = new LinkedHashMap<>();   // lower-case nickname -> nickname (removed by the host)
    private final Map<String, Handler> waiting = new LinkedHashMap<>();  // removed users who are asking to come back
    private java.util.concurrent.ScheduledExecutorService timerExec;
    private java.util.concurrent.ScheduledFuture<?> timerTask;   // guarded by lock
    private volatile long timerEndAt;                            // 0 = no study timer running

    private final Consumer<String> logger;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    public StudyServer(Consumer<String> logger) {
        this.logger = logger;
    }

    // ------------------------------------------------------------- lifecycle

    public void start(int port) throws IOException {
        if (running) {
            throw new IllegalStateException("Server is already running.");
        }
        serverSocket = new ServerSocket(port);
        pool = Executors.newCachedThreadPool();
        timerExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "study-timer");
            t.setDaemon(true);
            return t;
        });
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log("Server started on port " + getPort());
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** Only for tests: shorten the "can edit for 5 minutes" rule. */
    void setEditWindowMillis(long millis) {
        this.editWindowMillis = millis;
    }

    // ------------------------------------------------ host controls (server window)

    // ------------------------------------------------ quiz (host controls)

    /**
     * The host sends a multiple-choice question to everybody.
     * @param rawOptions up to 4 choices (empty ones are skipped); correctIndex counts from 0 in THIS list
     * @return an error message, or null when the question was sent
     */
    public String startQuiz(String rawQuestion, List<String> rawOptions, int correctIndex, int seconds) {
        if (!running) {
            return "The server is not running.";
        }
        String question = Protocol.clean(rawQuestion);
        if (question.isEmpty()) {
            return "Type the question first.";
        }
        if (question.length() > Protocol.MAX_QUIZ_QUESTION) {
            return "The question is too long (max " + Protocol.MAX_QUIZ_QUESTION + " characters).";
        }
        if (rawOptions.size() > Protocol.MAX_QUIZ_OPTIONS) {
            return "Use at most " + Protocol.MAX_QUIZ_OPTIONS + " answer choices.";
        }
        List<String> options = new ArrayList<>();
        int correct = -1;
        for (int i = 0; i < rawOptions.size(); i++) {
            String option = Protocol.clean(rawOptions.get(i));
            if (option.isEmpty()) {
                continue;
            }
            if (option.length() > Protocol.MAX_QUIZ_OPTION) {
                return "An answer choice is too long (max " + Protocol.MAX_QUIZ_OPTION + " characters).";
            }
            if (i == correctIndex) {
                correct = options.size();
            }
            options.add(option);
        }
        if (options.size() < Protocol.MIN_QUIZ_OPTIONS) {
            return "Give at least " + Protocol.MIN_QUIZ_OPTIONS + " answer choices.";
        }
        if (correct < 0) {
            return "Mark which answer is correct (it cannot be an empty choice).";
        }
        if (seconds < Protocol.MIN_QUIZ_SECONDS || seconds > Protocol.MAX_QUIZ_SECONDS) {
            return "The time to answer must be from " + Protocol.MIN_QUIZ_SECONDS + " to " + Protocol.MAX_QUIZ_SECONDS + " seconds.";
        }
        final QuizState q;
        synchronized (lock) {
            if (quiz != null && quiz.open) {
                return "A question is still open. Wait until it finishes, or end it first.";
            }
            q = new QuizState(nextQuizId++, question, options.toArray(new String[0]), correct,
                    System.currentTimeMillis() + seconds * 1000L);
            quiz = q;
            q.task = timerExec.schedule(() -> finishQuiz(q.id), seconds, java.util.concurrent.TimeUnit.SECONDS);
        }
        log("Quiz question " + q.id + " started: " + question);
        broadcast(quizLine(q, seconds));
        broadcast(system("Quiz question " + q.id + " started! Open the Quiz tab and pick your answer ("
                + seconds + " seconds)."));
        return null;
    }

    private String quizLine(QuizState q, long secondsLeft) {
        List<String> parts = new ArrayList<>();
        parts.add(Protocol.S_QUIZ);
        parts.add(String.valueOf(q.id));
        parts.add(String.valueOf(Math.max(1, secondsLeft)));
        parts.add(q.question);
        parts.add(String.valueOf(q.options.length));
        parts.addAll(Arrays.asList(q.options));
        return Protocol.line(parts.toArray(new String[0]));
    }

    /** Closes the question (time is up, or the host ended it): marks the answers and shares the result. */
    private void finishQuiz(int quizId) {
        final QuizState q;
        String result;
        String board;
        String summary;
        synchronized (lock) {
            q = quiz;
            if (q == null || q.id != quizId || !q.open) {
                return;
            }
            q.open = false;
            if (q.task != null) {
                q.task.cancel(false);
            }
            int[] counts = new int[q.options.length];
            int right = 0;
            for (Map.Entry<Integer, Integer> e : q.choices.entrySet()) {
                int choice = e.getValue();
                counts[choice]++;
                Handler h = clients.get(e.getKey());
                final String name = h != null ? h.nick : q.names.get(e.getKey());   // the current nickname if they renamed
                Score score = scores.get(name.toLowerCase());
                if (score == null) {
                    score = new Score(name);
                    scores.put(name.toLowerCase(), score);
                }
                score.name = name;
                score.answered++;
                if (choice == q.correct) {
                    score.correct++;
                    right++;
                }
            }
            List<String> parts = new ArrayList<>();
            parts.add(Protocol.S_QUIZ_RESULT);
            parts.add(String.valueOf(q.id));
            parts.add(String.valueOf(q.correct));
            parts.add(String.valueOf(counts.length));
            for (int c : counts) {
                parts.add(String.valueOf(c));
            }
            result = Protocol.line(parts.toArray(new String[0]));
            board = boardLine();
            summary = "Quiz question " + q.id + " finished. Correct answer: "
                    + (char) ('A' + q.correct) + ". " + q.options[q.correct] + " - "
                    + right + " of " + q.choices.size() + " answered correctly.";
        }
        log(summary);
        broadcast(result);
        broadcast(board);
        broadcast(system(summary));
    }

    /** The host closes the open question right away. */
    public void endQuiz() {
        int id;
        synchronized (lock) {
            if (quiz == null || !quiz.open) {
                return;
            }
            id = quiz.id;
        }
        finishQuiz(id);
    }

    /** Clears every score (a fresh leaderboard). */
    public void resetQuizScores() {
        String board;
        synchronized (lock) {
            scores.clear();
            board = boardLine();
        }
        log("Quiz leaderboard reset by the host.");
        broadcast(board);
        broadcast(system("The quiz leaderboard was reset by the host."));
    }

    public boolean isQuizOpen() {
        synchronized (lock) {
            return quiz != null && quiz.open;
        }
    }

    public String quizStatus() {
        synchronized (lock) {
            if (quiz != null && quiz.open) {
                long left = Math.max(0, (quiz.endsAt - System.currentTimeMillis() + 999) / 1000);
                return "Question " + quiz.id + " is open: " + left + " s left, " + quiz.choices.size() + " answered";
            }
        }
        return "No question is open.";
    }

    /** Leaderboard as text lines for the server window: most correct answers first. */
    public List<String> getLeaderboardLines() {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            List<Score> list = sortedScores();
            for (int i = 0; i < list.size(); i++) {
                Score s = list.get(i);
                lines.add((i + 1) + ". " + s.name + "  -  " + s.correct + " correct (" + s.answered + " answered)");
            }
        }
        return lines;
    }

    /** Most correct first; with the same number correct, the one who answered fewer questions (better accuracy). Caller holds 'lock'. */
    private List<Score> sortedScores() {
        List<Score> list = new ArrayList<>(scores.values());
        Collections.sort(list, (a, b) -> {
            if (a.correct != b.correct) {
                return b.correct - a.correct;
            }
            if (a.answered != b.answered) {
                return a.answered - b.answered;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    /** QZBOARD line (top 50). Caller holds 'lock'. */
    private String boardLine() {
        List<Score> list = sortedScores();
        int n = Math.min(list.size(), 50);
        StringBuilder sb = new StringBuilder(Protocol.S_QUIZ_BOARD).append('\t').append(n);
        for (int i = 0; i < n; i++) {
            Score s = list.get(i);
            sb.append('\t').append(s.name).append('\t').append(s.correct).append('\t').append(s.answered);
        }
        return sb.toString();
    }

    /** After a nickname change the score follows the person. Caller holds 'lock'. */
    private void renameScore(String oldNick, String newNick) {
        Score s = scores.remove(oldNick.toLowerCase());
        if (s == null) {
            return;
        }
        Score existing = scores.get(newNick.toLowerCase());
        if (existing != null) {   // an old score under that name: add them together
            existing.correct += s.correct;
            existing.answered += s.answered;
            existing.name = newNick;
        } else {
            s.name = newNick;
            scores.put(newNick.toLowerCase(), s);
        }
    }

    private void broadcastQuizBoardIfAny() {
        String board = null;
        synchronized (lock) {
            if (!scores.isEmpty()) {
                board = boardLine();
            }
        }
        if (board != null) {
            broadcast(board);
        }
    }

    /** A user the host removed. 'waiting' = they tried to join again and wait for the host's decision. */
    public static class RemovedUser {
        public final String nick;
        public final boolean waiting;

        RemovedUser(String nick, boolean waiting) {
            this.nick = nick;
            this.waiting = waiting;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RemovedUser && ((RemovedUser) o).nick.equals(nick) && ((RemovedUser) o).waiting == waiting;
        }

        @Override
        public int hashCode() {
            return nick.hashCode() * 31 + (waiting ? 1 : 0);
        }

        @Override
        public String toString() {
            return waiting ? nick + "  -  WAITING for your permission" : nick + "  -  removed";
        }
    }

    /** Removed users; the ones asking to come back are listed first. */
    public List<RemovedUser> getRemovedUsers() {
        List<RemovedUser> list = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, String> e : blocked.entrySet()) {
                list.add(new RemovedUser(e.getValue(), waiting.containsKey(e.getKey())));
            }
        }
        Collections.sort(list, (x, y) -> x.waiting == y.waiting ? 0 : (x.waiting ? -1 : 1));
        return list;
    }

    /** The host allows a removed user back (even before they ask). If they are waiting, they join automatically. */
    public boolean allowUser(String name) {
        String key = name.toLowerCase();
        Handler w;
        synchronized (lock) {
            if (blocked.remove(key) == null) {
                return false;
            }
            w = waiting.remove(key);
        }
        log("Host allowed " + name + " to join again.");
        if (w != null) {
            w.send(Protocol.S_ALLOWED);
        }
        return true;
    }

    /** The host refuses a waiting user. They stay removed and their connection closes. */
    public boolean denyUser(String name) {
        String key = name.toLowerCase();
        Handler w;
        synchronized (lock) {
            if (!blocked.containsKey(key)) {
                return false;
            }
            w = waiting.remove(key);
        }
        if (w == null) {
            return false;
        }
        log("Host refused " + name + " to join again.");
        w.send(Protocol.S_DENIED);
        w.close();
        return true;
    }

    /** Nicknames of everyone who is online. */
    public List<String> getUserNames() {
        List<String> names = new ArrayList<>();
        synchronized (lock) {
            for (Handler h : clients.values()) {
                names.add(h.nick);
            }
        }
        return names;
    }

    /** Removes a user from the chat. @return false when nobody has that nickname. */
    public boolean kickUser(String name) {
        Handler target = null;
        synchronized (lock) {
            for (Handler h : clients.values()) {
                if (h.nick.equalsIgnoreCase(name)) {
                    target = h;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        synchronized (lock) {
            blocked.put(target.nick.toLowerCase(), target.nick);   // must ask the host before joining again
        }
        log("Host removed " + target.nick + ".");
        target.kickedByHost();
        return true;
    }

    /** Sends an announcement to everybody. @return an error message, or null when sent. */
    public String announce(String raw) {
        String text = Protocol.clean(raw);
        if (!running) {
            return "The server is not running.";
        }
        if (text.isEmpty()) {
            return "Type the announcement first.";
        }
        if (text.length() > Protocol.MAX_REPLY) {
            return "The announcement is too long (max " + Protocol.MAX_REPLY + " characters).";
        }
        log("Host announcement: " + text);
        broadcast(Protocol.line(Protocol.S_ANNOUNCE, now(), text));
        return null;
    }

    /** Starts (or restarts) the shared study timer. @return an error message, or null when started. */
    /** The units the host can pick for the study timer. */
    public enum TimerUnit {
        MINUTES("minute", 1), HOURS("hour", 60), DAYS("day", 24 * 60);

        final String word;
        final int minutes;

        TimerUnit(String word, int minutes) {
            this.word = word;
            this.minutes = minutes;
        }

        /** Biggest number allowed for this unit (the timer can last at most 30 days). */
        public int maxAmount() {
            return Protocol.MAX_TIMER_MINUTES / minutes;
        }

        String describe(int amount) {
            return amount + " " + word + (amount == 1 ? "" : "s");
        }
    }

    /** Starts (or restarts) the shared study timer, e.g. 25 minutes, 2 hours or 1 day. @return an error message, or null. */
    public String startTimer(int amount, TimerUnit unit) {
        if (amount < 1 || amount > unit.maxAmount()) {
            return "Choose a number from 1 to " + unit.maxAmount() + " " + unit.word + "s (the longest timer is 30 days).";
        }
        return startTimerMillis((long) amount * unit.minutes * 60_000L, unit.describe(amount));
    }

    public String startTimer(int minutes) {
        return startTimer(minutes, TimerUnit.MINUTES);
    }

    String startTimerMillis(long millis, String description) {
        if (!running) {
            return "The server is not running.";
        }
        final long endAt = System.currentTimeMillis() + millis;
        synchronized (lock) {
            if (timerTask != null) {
                timerTask.cancel(false);
            }
            timerEndAt = endAt;
            timerTask = timerExec.schedule(() -> finishTimer(endAt), millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        log("Study timer started: " + description + ".");
        broadcast(Protocol.line(Protocol.S_TIMER, String.valueOf((millis + 999) / 1000), "running"));
        broadcast(system("Study timer started: " + description + ". Focus!"));
        return null;
    }

    private void finishTimer(long endAt) {
        synchronized (lock) {
            if (timerEndAt != endAt) {
                return;   // it was stopped or restarted meanwhile
            }
            timerEndAt = 0;
            timerTask = null;
        }
        log("Study timer finished.");
        broadcast(Protocol.line(Protocol.S_TIMER, "0", "finished"));
        broadcast(system("Study timer finished! Time for a break."));
    }

    public void stopTimer() {
        boolean wasRunning;
        synchronized (lock) {
            wasRunning = timerEndAt != 0;
            timerEndAt = 0;
            if (timerTask != null) {
                timerTask.cancel(false);
                timerTask = null;
            }
        }
        if (wasRunning && running) {
            log("Study timer stopped by the host.");
            broadcast(Protocol.line(Protocol.S_TIMER, "0", "stopped"));
            broadcast(system("Study timer stopped by the host."));
        }
    }

    /** Seconds left on the study timer; 0 when none is running. */
    public long timerSecondsLeft() {
        long end = timerEndAt;
        return end == 0 ? 0 : Math.max(0, (end - System.currentTimeMillis() + 999) / 1000);
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                pool.execute(new Handler(s));
            } catch (IOException e) {
                if (running) {
                    log("Accept error: " + e.getMessage());
                }
            } catch (RejectedExecutionException e) {
                break;
            }
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Handler h : new ArrayList<>(allHandlers)) {
            h.close();
        }
        synchronized (lock) {
            clients.clear();
            questions.clear();
            messages.clear();
            blocked.clear();
            waiting.clear();
            quiz = null;
            scores.clear();
            poll = null;
            timerEndAt = 0;
        }
        timerExec.shutdownNow();
        pool.shutdownNow();
        log("Server stopped.");
    }

    private void log(String msg) {
        if (logger != null) {
            logger.accept("[" + LocalTime.now().format(TIME) + "] " + msg);
        }
    }

    // ------------------------------------------------------- message builders
    // (callers of the *Line methods must hold 'lock')

    private static String now() {
        return LocalTime.now().format(TIME);
    }

    private static String system(String text) {
        return Protocol.line(Protocol.S_SYS, now(), text);
    }

    private static String error(String text) {
        return Protocol.line(Protocol.S_ERROR, text);
    }

    private String usersLine() {
        StringBuilder sb = new StringBuilder(Protocol.S_USERS);
        for (Handler h : clients.values()) {
            sb.append('\t').append(h.nick);
        }
        return sb.toString();
    }

    /** Lowest name colour nobody online uses; when all are taken, keep cycling. (Caller holds 'lock'.) */
    private int freeColor() {
        boolean[] used = new boolean[Protocol.NAME_COLORS];
        for (Handler h : clients.values()) {
            used[h.colorIdx % Protocol.NAME_COLORS] = true;
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return nextColor++ % Protocol.NAME_COLORS;
    }

    private String colorsLine() {
        StringBuilder sb = new StringBuilder(Protocol.S_COLORS);
        for (Handler h : clients.values()) {
            sb.append('\t').append(h.nick).append('\t').append(h.colorIdx);
        }
        return sb.toString();
    }

    private String queueLine(int clientId) {
        List<Question> sorted = new ArrayList<>(questions);
        Collections.sort(sorted, (a, b) -> {
            if (a.resolved != b.resolved) {
                return a.resolved ? 1 : -1;                       // open questions first
            }
            if (a.upvoters.size() != b.upvoters.size()) {
                return b.upvoters.size() - a.upvoters.size();     // most votes first
            }
            return a.id - b.id;                                   // oldest first
        });
        StringBuilder sb = new StringBuilder(Protocol.S_QUEUE).append('\t').append(sorted.size());
        for (Question q : sorted) {
            sb.append('\t').append(q.id)
              .append('\t').append(q.upvoters.size())
              .append('\t').append(q.resolved ? 1 : 0)
              .append('\t').append(q.upvoters.contains(clientId) ? 1 : 0)
              .append('\t').append(q.askerNick)
              .append('\t').append(q.text)
              .append('\t').append(q.replies.size());
            for (Reply r : q.replies) {
                sb.append('\t').append(r.id)
                  .append('\t').append(r.authorNick)
                  .append('\t').append(r.time)
                  .append('\t').append(r.text);
            }
        }
        return sb.toString();
    }

    private String pollLine(int clientId) {
        if (poll == null) {
            return Protocol.line(Protocol.S_POLL, "NONE");
        }
        int[] counts = new int[poll.options.size()];
        for (int choice : poll.votes.values()) {
            counts[choice]++;
        }
        Integer mine = poll.votes.get(clientId);
        StringBuilder sb = new StringBuilder(Protocol.S_POLL);
        sb.append('\t').append(poll.id)
          .append('\t').append(poll.creatorNick)
          .append('\t').append(poll.closed ? 1 : 0)
          .append('\t').append(poll.question)
          .append('\t').append(mine == null ? -1 : mine)
          .append('\t').append(counts.length);
        for (int i = 0; i < counts.length; i++) {
            sb.append('\t').append(poll.options.get(i)).append('\t').append(counts[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ broadcasting

    private List<Handler> readyHandlers() {
        synchronized (lock) {
            List<Handler> list = new ArrayList<>();
            for (Handler h : clients.values()) {
                if (h.ready) {
                    list.add(h);
                }
            }
            return list;
        }
    }

    /** Same line to everybody. */
    private void broadcast(String line) {
        for (Handler h : readyHandlers()) {
            h.send(line);
        }
    }

    private void broadcastUsers() {
        String line;
        String colors;
        synchronized (lock) {
            line = usersLine();
            colors = colorsLine();
        }
        broadcast(line);
        broadcast(colors);   // each user keeps the same name colour for as long as they stay
    }

    /** The queue and poll lines contain a per-user flag ("did I vote?"), so each user gets their own line. */
    private void broadcastQueue() {
        Map<Handler, String> lines = new LinkedHashMap<>();
        synchronized (lock) {
            for (Handler h : clients.values()) {
                if (h.ready) {
                    lines.put(h, queueLine(h.id));
                }
            }
        }
        for (Map.Entry<Handler, String> e : lines.entrySet()) {
            e.getKey().send(e.getValue());
        }
    }

    private void broadcastPoll() {
        Map<Handler, String> lines = new LinkedHashMap<>();
        synchronized (lock) {
            for (Handler h : clients.values()) {
                if (h.ready) {
                    lines.put(h, pollLine(h.id));
                }
            }
        }
        for (Map.Entry<Handler, String> e : lines.entrySet()) {
            e.getKey().send(e.getValue());
        }
    }

    private boolean nickTaken(String name, Handler self) {
        for (Handler h : clients.values()) {
            if (h != self && h.nick.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static int toInt(String[] f, int index) {
        try {
            return Integer.parseInt(f[index].trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------- client handler

    private class Handler implements Runnable {
        private final Socket socket;
        private volatile BufferedReader in;
        private volatile PrintWriter out;
        private final Object writeLock = new Object();
        private volatile boolean closed;

        int id;
        volatile String nick = "?";
        volatile boolean joined;  // nickname reserved
        volatile boolean ready;   // JOINED reply sent; may receive broadcasts
        volatile boolean kicked;  // removed by the host
        volatile int colorIdx;    // this user's name colour (see Protocol.NAME_COLORS)

        Handler(Socket socket) {
            this.socket = socket;
            allHandlers.add(this);
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(30000); // a connection that never sends JOIN is dropped
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    if (line.length() > 5000) {
                        send(error("Message too long."));
                        continue;
                    }
                    handle(line);
                }
            } catch (SocketTimeoutException e) {
                log("Dropped a connection that did not join in time.");
            } catch (IOException e) {
                // client vanished (closed window, network cable, ...) - normal, handled in leave()
            } finally {
                leave();
            }
        }

        void send(String line) {
            synchronized (writeLock) {
                PrintWriter w = out;
                if (w == null || closed) {
                    return;
                }
                w.print(line);
                w.print('\n');
                w.flush();
                if (w.checkError()) {
                    close();
                }
            }
        }

        void close() {
            closed = true;
            try {
                socket.close();   // also unblocks readLine() in run()
            } catch (IOException ignored) {
            }
        }

        /** Called from the server window: tell the user, then disconnect them. */
        void kickedByHost() {
            kicked = true;
            send(Protocol.S_KICKED);
            send(Protocol.S_BYE);
            close();
        }

        private void leave() {
            synchronized (lock) {
                waiting.values().remove(this);   // someone who was waiting gave up (or the connection dropped)
            }
            boolean wasJoined = false;
            boolean pollClosed = false;
            synchronized (lock) {
                if (joined && clients.get(id) == this) {
                    clients.remove(id);
                    wasJoined = true;
                    if (poll != null && !poll.closed && poll.creatorId == id) {
                        poll.closed = true;   // nobody could close it any more
                        pollClosed = true;
                    }
                }
            }
            close();
            allHandlers.remove(this);
            if (wasJoined && running) {
                log(nick + " left.");
                broadcast(system(nick + (kicked ? " was removed by the host." : " left the chat.")));
                broadcastUsers();
                if (pollClosed) {
                    broadcast(system("The poll was closed because " + nick + " left."));
                    broadcastPoll();
                }
                broadcastQueue(); // "who can resolve" may have changed
            }
        }

        // ---- request dispatch ----

        private void handle(String line) {
            String[] f = line.split("\t", -1);
            String cmd = f[0];
            if (!joined) {
                if (Protocol.C_JOIN.equals(cmd)) {
                    join(f.length > 1 ? f[1] : "");
                } else {
                    send(error("Please join with a nickname first."));
                    close();
                }
                return;
            }
            switch (cmd) {
                case Protocol.C_CHAT:
                    chat(f.length > 1 ? f[1] : "");
                    break;
                case Protocol.C_QUIZ_ANSWER:
                    quizAnswer(toInt(f, 1), toInt(f, 2));
                    break;
                case Protocol.C_PM:
                    privateMessage(f.length > 1 ? f[1] : "", f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_REPLY_MSG:
                    replyToMessage(toInt(f, 1), f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_EDIT:
                    editMessage(toInt(f, 1), f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_DELETE:
                    deleteMessage(toInt(f, 1));
                    break;
                case Protocol.C_ASK:
                    ask(f.length > 1 ? f[1] : "");
                    break;
                case Protocol.C_UPVOTE:
                    upvote(toInt(f, 1));
                    break;
                case Protocol.C_UNRESOLVE:
                    setResolved(toInt(f, 1), false);
                    break;
                case Protocol.C_RESOLVE:
                    resolve(toInt(f, 1));
                    break;
                case Protocol.C_Q_EDIT:
                    editQuestion(toInt(f, 1), f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_Q_DELETE:
                    deleteQuestion(toInt(f, 1));
                    break;
                case Protocol.C_Q_REPLY:
                    replyToQuestion(toInt(f, 1), f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_R_EDIT:
                    editReply(toInt(f, 1), f.length > 2 ? f[2] : "");
                    break;
                case Protocol.C_R_DELETE:
                    deleteReply(toInt(f, 1));
                    break;
                case Protocol.C_POLL_START:
                    startPoll(f);
                    break;
                case Protocol.C_VOTE:
                    vote(toInt(f, 1), toInt(f, 2));
                    break;
                case Protocol.C_POLL_END:
                    endPoll(toInt(f, 1));
                    break;
                default:
                    send(error("Unknown request."));
            }
        }

        // ---- join ----

        private void join(String requested) {
            String name = Protocol.clean(requested);
            String err = Protocol.checkNick(name);
            boolean mustWait = false;
            Handler replaced = null;
            if (err == null) {
                synchronized (lock) {
                    String key = name.toLowerCase();
                    if (blocked.containsKey(key)) {
                        // the host removed this nickname earlier: it needs the host's permission to come back
                        if (waiting.size() >= MAX_WAITING && !waiting.containsKey(key)) {
                            err = "Too many people are waiting for the host's permission. Try again later.";
                        } else {
                            replaced = waiting.put(key, this);
                            mustWait = true;
                        }
                    } else if (nickTaken(name, this)) {
                        err = "Nickname '" + name + "' is already in use. Choose another one.";
                    } else {
                        id = nextClientId++;
                        nick = name;
                        colorIdx = freeColor();
                        joined = true;
                        clients.put(id, this);
                    }
                }
            }
            if (err != null) {
                send(error(err));
                close();
                return;
            }
            if (mustWait) {
                if (replaced != null && replaced != this) {
                    replaced.close();   // an older request with the same nickname
                }
                try {
                    socket.setSoTimeout(0);   // the host may take a while to decide
                } catch (SocketException ignored) {
                }
                log(name + " (" + socket.getInetAddress().getHostAddress()
                        + ") was removed earlier and wants to join again - waiting for the host's permission.");
                send(Protocol.line(Protocol.S_WAIT, "The host removed you earlier. Your request to join again was "
                        + "sent to the host. Please wait for the host to allow you."));
                return;
            }
            try {
                socket.setSoTimeout(0);
            } catch (SocketException ignored) {
            }
            send(Protocol.line(Protocol.S_JOINED, nick));
            ready = true;
            log(nick + " joined from " + socket.getInetAddress().getHostAddress());

            String queue;
            String pollInfo;
            synchronized (lock) {
                queue = queueLine(id);
                pollInfo = pollLine(id);
            }
            send(system("Welcome, " + nick + "! Type /help for commands."));
            broadcast(system(nick + " joined the chat."));
            broadcastUsers();
            send(queue);
            send(pollInfo);
            long left = timerSecondsLeft();
            if (left > 0) {
                send(Protocol.line(Protocol.S_TIMER, String.valueOf(left), "running"));
            }
            String quizNow = null;
            String boardNow = null;
            synchronized (lock) {
                if (quiz != null && quiz.open) {
                    quizNow = quizLine(quiz, (quiz.endsAt - System.currentTimeMillis() + 999) / 1000);
                }
                if (!scores.isEmpty()) {
                    boardNow = boardLine();
                }
            }
            if (boardNow != null) {
                send(boardNow);   // the leaderboard so far
            }
            if (quizNow != null) {
                send(quizNow);    // a question that is still open can be answered by the newcomer too
            }
        }

        // ---- chat + commands ----

        private void chat(String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Cannot send a blank message."));
                return;
            }
            if (text.length() > Protocol.MAX_CHAT) {
                send(error("Message is too long (max " + Protocol.MAX_CHAT + " characters)."));
                return;
            }
            if (text.startsWith("/")) {
                command(text);
                return;
            }
            int msgId;
            synchronized (lock) {
                msgId = nextMsgId++;
                messages.put(msgId, new ChatMsg(msgId, id, nick, text, System.currentTimeMillis()));
                if (messages.size() > MAX_STORED_MESSAGES) {
                    Iterator<Integer> oldest = messages.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
            }
            // everyone, including the sender
            broadcast(Protocol.line(Protocol.S_MSG, now(), nick, text, String.valueOf(msgId)));
        }

        // ---- quiz: one answer per question ----

        private void quizAnswer(int quizId, int option) {
            String err = null;
            synchronized (lock) {
                QuizState q = quiz;
                if (q == null || q.id != quizId) {
                    err = "There is no such quiz question.";
                } else if (!q.open || System.currentTimeMillis() > q.endsAt) {
                    err = "Time is up: this question is closed.";
                } else if (option < 0 || option >= q.options.length) {
                    err = "Pick one of the answer choices.";
                } else if (q.choices.containsKey(id)) {
                    err = "You already answered this question.";
                } else {
                    q.choices.put(id, option);
                    q.names.put(id, nick);
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            send(Protocol.line(Protocol.S_QUIZ_ACK, String.valueOf(quizId), String.valueOf(option)));
        }

        // ---- private message: only the sender and the receiver get it ----

        private void privateMessage(String toNick, String raw) {
            String target = Protocol.clean(toNick);
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Cannot send a blank message."));
                return;
            }
            if (text.length() > Protocol.MAX_CHAT) {
                send(error("Message is too long (max " + Protocol.MAX_CHAT + " characters)."));
                return;
            }
            Handler to = null;
            synchronized (lock) {
                for (Handler h : clients.values()) {
                    if (h.ready && h.nick.equalsIgnoreCase(target)) {
                        to = h;
                        break;
                    }
                }
            }
            if (to == null) {
                send(error("No online user named '" + target + "'."));
                return;
            }
            if (to == this) {
                send(error("You cannot send a private message to yourself."));
                return;
            }
            String line = Protocol.line(Protocol.S_PM, now(), nick, to.nick, text);
            to.send(line);
            send(line);   // the sender sees their own private message too
        }

        // ---- reply to a chat message (shows what is being answered) ----

        private void replyToMessage(int targetId, String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Cannot send a blank message."));
                return;
            }
            if (text.length() > Protocol.MAX_CHAT) {
                send(error("Message is too long (max " + Protocol.MAX_CHAT + " characters)."));
                return;
            }
            String err = null;
            String replySender = "";
            String replyPreview = "";
            int msgId = 0;
            synchronized (lock) {
                ChatMsg target = messages.get(targetId);
                if (target == null) {
                    err = "That message can no longer be replied to.";
                } else if (target.deleted) {
                    err = "That message was deleted.";
                } else {
                    replySender = target.senderNick;
                    replyPreview = shorten(target.text, 50);
                    msgId = nextMsgId++;
                    messages.put(msgId, new ChatMsg(msgId, id, nick, text, System.currentTimeMillis()));
                    if (messages.size() > MAX_STORED_MESSAGES) {
                        Iterator<Integer> oldest = messages.keySet().iterator();
                        oldest.next();
                        oldest.remove();
                    }
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            broadcast(Protocol.line(Protocol.S_MSG, now(), nick, text, String.valueOf(msgId), replySender, replyPreview));
        }

        // ---- edit / delete your own message ----

        private void editMessage(int msgId, String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Cannot send a blank message."));
                return;
            }
            if (text.length() > Protocol.MAX_CHAT) {
                send(error("Message is too long (max " + Protocol.MAX_CHAT + " characters)."));
                return;
            }
            String err = null;
            synchronized (lock) {
                ChatMsg m = messages.get(msgId);
                if (m == null) {
                    err = "That message can no longer be edited.";
                } else if (m.ownerId != id) {
                    err = "You can only edit your own messages.";
                } else if (m.deleted) {
                    err = "That message was deleted.";
                } else if (System.currentTimeMillis() - m.createdAt > editWindowMillis) {
                    err = "You can only edit a message within 5 minutes of sending it.";
                } else if (m.text.equals(text)) {
                    err = "The message is unchanged.";
                } else {
                    m.text = text;
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            broadcast(Protocol.line(Protocol.S_EDITED, String.valueOf(msgId), text));
        }

        private void deleteMessage(int msgId) {
            String err = null;
            synchronized (lock) {
                ChatMsg m = messages.get(msgId);
                if (m == null) {
                    err = "That message can no longer be deleted.";
                } else if (m.ownerId != id) {
                    err = "You can only delete your own messages.";
                } else if (m.deleted) {
                    err = "That message was already deleted.";
                } else {
                    m.deleted = true;
                    m.text = "";
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            broadcast(Protocol.line(Protocol.S_DELETED, String.valueOf(msgId)));
        }

        private void command(String text) {
            String[] p = text.split("\\s+", 2);
            switch (p[0].toLowerCase()) {
                case "/name":
                    rename(p.length > 1 ? p[1] : "");
                    break;
                case "/msg": {
                    String[] q = (p.length > 1 ? p[1] : "").split("\\s+", 2);
                    if (q.length < 2 || q[0].isEmpty()) {
                        send(error("Usage: /msg <nickname> <message>"));
                    } else {
                        privateMessage(q[0], q[1]);
                    }
                    break;
                }
                case "/exit":
                    send(Protocol.S_BYE);
                    close();
                    break;
                case "/help":
                    send(system("Commands: /name <newname> = change nickname | /msg <nickname> <text> = private message | /exit = leave | /help = this list"));
                    send(system("Click one of your own messages, then use Edit (within 5 minutes) or Delete under the chat box."));
                    send(system("Use the Questions tab for the shared question queue, the Poll tab for live polls and the Quiz tab for the host's quiz."));
                    break;
                default:
                    send(error("Unknown command '" + p[0] + "'. Type /help."));
            }
        }

        private void rename(String requested) {
            String name = Protocol.clean(requested);
            String err = Protocol.checkNick(name);
            String old = nick;
            if (err == null) {
                synchronized (lock) {
                    if (name.equals(nick)) {
                        err = "That is already your nickname.";
                    } else if (nickTaken(name, this)) {
                        err = "Nickname '" + name + "' is already in use.";
                    } else {
                        nick = name;
                        for (Question q : questions) {
                            if (q.askerId == id) {
                                q.askerNick = name;
                            }
                            for (Reply r : q.replies) {
                                if (r.authorId == id) {
                                    r.authorNick = name;
                                }
                            }
                        }
                        if (poll != null && poll.creatorId == id) {
                            poll.creatorNick = name;
                        }
                        renameScore(old, name);
                    }
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            log(old + " is now " + name);
            send(Protocol.line(Protocol.S_NICK, name));
            broadcast(system(old + " is now known as " + name + "."));
            broadcastUsers();
            broadcastQueue();
            broadcastPoll();
            broadcastQuizBoardIfAny();
        }

        // ---- feature 1: question queue ----

        private void ask(String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Type a question first."));
                return;
            }
            if (text.length() > Protocol.MAX_QUESTION) {
                send(error("Question is too long (max " + Protocol.MAX_QUESTION + " characters)."));
                return;
            }
            synchronized (lock) {
                if (questions.size() >= MAX_QUESTIONS) {
                    send(error("The question queue is full."));
                    return;
                }
                questions.add(new Question(nextQuestionId++, id, nick, text));
            }
            broadcast(system(nick + " added a question to the queue."));
            broadcastQueue();
        }

        private Question findQuestion(int qid) {
            for (Question q : questions) {
                if (q.id == qid) {
                    return q;
                }
            }
            return null;
        }

        private void upvote(int qid) {
            boolean found = false;
            synchronized (lock) {
                Question q = findQuestion(qid);
                if (q != null) {
                    found = true;
                    if (!q.upvoters.add(id)) {
                        q.upvoters.remove(id);   // second click removes the vote
                    }
                }
            }
            if (found) {
                broadcastQueue();
            } else {
                send(error("That question no longer exists."));
            }
        }

        private void editQuestion(int qid, String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Type a question first."));
                return;
            }
            if (text.length() > Protocol.MAX_QUESTION) {
                send(error("Question is too long (max " + Protocol.MAX_QUESTION + " characters)."));
                return;
            }
            String err = null;
            synchronized (lock) {
                Question q = findQuestion(qid);
                if (q == null) {
                    err = "That question no longer exists.";
                } else if (q.askerId != id) {
                    err = "You can only edit your own questions.";
                } else if (q.text.equals(text)) {
                    err = "The question is unchanged.";
                } else {
                    q.text = text;
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastQueue();
            }
        }

        private void deleteQuestion(int qid) {
            String err = null;
            synchronized (lock) {
                Question q = findQuestion(qid);
                if (q == null) {
                    err = "That question no longer exists.";
                } else if (q.askerId != id) {
                    err = "You can only delete your own questions.";
                } else {
                    questions.remove(q);
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastQueue();
            }
        }

        private String shorten(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max - 3) + "...";
        }

        private void replyToQuestion(int qid, String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Type your reply first."));
                return;
            }
            if (text.length() > Protocol.MAX_REPLY) {
                send(error("Reply is too long (max " + Protocol.MAX_REPLY + " characters)."));
                return;
            }
            String err = null;
            String asker = "";
            String questionText = "";
            synchronized (lock) {
                Question q = findQuestion(qid);
                if (q == null) {
                    err = "That question no longer exists.";
                } else if (q.replies.size() >= Protocol.MAX_REPLIES_PER_QUESTION) {
                    err = "This question already has " + Protocol.MAX_REPLIES_PER_QUESTION + " replies.";
                } else {
                    q.replies.add(new Reply(nextReplyId++, id, nick, now(), text));
                    asker = q.askerNick;
                    questionText = q.text;
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            // Say WHICH question was answered and what the answer is, so the chat is easy to follow.
            broadcast(system(nick + " replied to " + asker + "'s question \"" + shorten(questionText, 60)
                    + "\": " + shorten(text, 120)));
            broadcastQueue();
        }

        /** Finds a reply by id in any question (caller holds 'lock'). */
        private Reply findReply(int rid, List<Question> holder) {
            for (Question q : questions) {
                for (Reply r : q.replies) {
                    if (r.id == rid) {
                        holder.add(q);
                        return r;
                    }
                }
            }
            return null;
        }

        private void editReply(int rid, String raw) {
            String text = Protocol.clean(raw);
            if (text.isEmpty()) {
                send(error("Type your reply first."));
                return;
            }
            if (text.length() > Protocol.MAX_REPLY) {
                send(error("Reply is too long (max " + Protocol.MAX_REPLY + " characters)."));
                return;
            }
            String err = null;
            synchronized (lock) {
                Reply r = findReply(rid, new ArrayList<Question>());
                if (r == null) {
                    err = "That reply no longer exists.";
                } else if (r.authorId != id) {
                    err = "You can only edit your own replies.";
                } else if (r.text.equals(text)) {
                    err = "The reply is unchanged.";
                } else {
                    r.text = text;
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastQueue();
            }
        }

        private void deleteReply(int rid) {
            String err = null;
            synchronized (lock) {
                List<Question> holder = new ArrayList<>();
                Reply r = findReply(rid, holder);
                if (r == null) {
                    err = "That reply no longer exists.";
                } else if (r.authorId != id) {
                    err = "You can only delete your own replies.";
                } else {
                    holder.get(0).replies.remove(r);
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastQueue();
            }
        }

        private void resolve(int qid) {
            changeResolved(qid, null);   // toggle
        }

        /** Explicitly sets resolved / not resolved (used by the Un-resolve button). */
        private void setResolved(int qid, boolean value) {
            changeResolved(qid, value);
        }

        private void changeResolved(int qid, Boolean value) {
            String err = null;
            synchronized (lock) {
                Question q = findQuestion(qid);
                if (q == null) {
                    err = "That question no longer exists.";
                } else if (q.askerId != id && clients.containsKey(q.askerId)) {
                    err = "Only " + q.askerNick + " can " + (Boolean.FALSE.equals(value) ? "reopen" : "mark")
                            + " this question" + (Boolean.FALSE.equals(value) ? "." : " as resolved.");
                } else {
                    q.resolved = (value == null) ? !q.resolved : value;
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastQueue();
            }
        }

        // ---- feature 2: live poll ----

        private void startPoll(String[] f) {
            String question = Protocol.clean(f.length > 1 ? f[1] : "");
            List<String> options = new ArrayList<>();
            for (int i = 2; i < f.length; i++) {
                String o = Protocol.clean(f[i]);
                if (!o.isEmpty()) {
                    options.add(o);
                }
            }
            String err = null;
            if (question.isEmpty()) {
                err = "A poll needs a question.";
            } else if (question.length() > Protocol.MAX_POLL_QUESTION) {
                err = "Poll question is too long (max " + Protocol.MAX_POLL_QUESTION + " characters).";
            } else if (options.size() < Protocol.MIN_OPTIONS || options.size() > Protocol.MAX_OPTIONS) {
                err = "A poll needs " + Protocol.MIN_OPTIONS + " to " + Protocol.MAX_OPTIONS + " options.";
            } else {
                for (String o : options) {
                    if (o.length() > Protocol.MAX_POLL_OPTION) {
                        err = "Poll options are limited to " + Protocol.MAX_POLL_OPTION + " characters.";
                    }
                }
            }
            if (err == null) {
                synchronized (lock) {
                    if (poll != null && !poll.closed) {
                        err = "A poll is already open. Wait for " + poll.creatorNick + " to close it.";
                    } else {
                        poll = new Poll(nextPollId++, id, nick, question, options);
                    }
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            broadcast(system(nick + " started a poll: " + question));
            broadcastPoll();
        }

        private void vote(int pollId, int option) {
            String err = null;
            synchronized (lock) {
                if (poll == null || poll.id != pollId) {
                    err = "That poll no longer exists.";
                } else if (poll.closed) {
                    err = "That poll is closed.";
                } else if (option < 0 || option >= poll.options.size()) {
                    err = "Invalid poll option.";
                } else {
                    poll.votes.put(id, option);   // voting again simply changes your vote
                }
            }
            if (err != null) {
                send(error(err));
            } else {
                broadcastPoll();
            }
        }

        private void endPoll(int pollId) {
            String err = null;
            synchronized (lock) {
                if (poll == null || poll.id != pollId) {
                    err = "That poll no longer exists.";
                } else if (poll.creatorId != id) {
                    err = "Only " + poll.creatorNick + " can close this poll.";
                } else if (!poll.closed) {
                    poll.closed = true;
                }
            }
            if (err != null) {
                send(error(err));
                return;
            }
            broadcast(system(nick + " closed the poll."));
            broadcastPoll();
        }
    }

    // ------------------------------------------------------------ utilities

    /** Non-loopback IPv4 addresses of this computer - what other computers type into the client. */
    public static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface ni = nics.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        result.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return result;
    }

    // ------------------------------------------------------------ server window

    private static class ServerWindow extends JFrame {
        private final JTextField portField;
        private final JButton toggleBtn = new JButton("Start server");
        private final JLabel statusLabel = new JLabel("Server stopped.");
        private final JLabel addressLabel = new JLabel(" ");
        private final JTextArea logArea = new JTextArea();
        private final StudyServer server;

        private final DefaultListModel<String> hostUserModel = new DefaultListModel<>();
        private final JList<String> hostUserList = new JList<>(hostUserModel);
        private final JButton kickBtn = new JButton("Kick selected user");
        private final JTextField announceField = new JTextField(16);
        private final JButton announceBtn = new JButton("Send announcement");
        private final JSpinner minutesSpinner = new JSpinner(new SpinnerNumberModel(25, 1, Protocol.MAX_TIMER_MINUTES, 1));
        private final JComboBox<String> unitBox = new JComboBox<>(new String[]{"Minutes", "Hours", "Days"});

        private final JTextField quizQuestionField = new JTextField();
        private final JTextField[] quizOptionFields = {new JTextField(), new JTextField(), new JTextField(), new JTextField()};
        private final JRadioButton[] quizCorrect = new JRadioButton[4];
        private final JSpinner quizSecondsSpinner = new JSpinner(new SpinnerNumberModel(20, Protocol.MIN_QUIZ_SECONDS, Protocol.MAX_QUIZ_SECONDS, 5));
        private final JButton quizStartBtn = new JButton("Start question");
        private final JButton quizEndBtn = new JButton("End question now");
        private final JButton quizResetBtn = new JButton("Reset leaderboard");
        private final JLabel quizStatusLabel = new JLabel("No question is open.");
        private final DefaultListModel<String> boardModel = new DefaultListModel<>();
        private final JList<String> boardList = new JList<>(boardModel);
        private final JButton startTimerBtn = new JButton("Start timer");
        private final JButton stopTimerBtn = new JButton("Stop timer");
        private final JLabel timerLabel = new JLabel("Timer: not running");
        private JPanel topPanel;
        private JPanel bottomPanel;

        private final DefaultListModel<StudyServer.RemovedUser> removedModel = new DefaultListModel<>();
        private final JList<StudyServer.RemovedUser> removedList = new JList<>(removedModel);
        private final JButton allowBtn = new JButton("Allow selected");
        private final JButton denyBtn = new JButton("Deny selected");
        private final javax.swing.border.TitledBorder removedBorder =
                BorderFactory.createTitledBorder("Removed users (they need your permission to come back)");
        private int lastWaiting;
        private JPanel removedBox;

        ServerWindow(int port) {
            super("Study Circle - Server");
            server = new StudyServer(msg -> SwingUtilities.invokeLater(() -> {
                logArea.append(msg + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }));
            portField = new JTextField(String.valueOf(port), 6);

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            top.setBackground(Theme.DEEP_ROSE);
            JLabel portLabel = new JLabel("Port:");
            portLabel.setForeground(Theme.BAR_TEXT);
            statusLabel.setForeground(Theme.BAR_TEXT);
            top.add(portLabel);
            top.add(portField);
            top.add(toggleBtn);
            top.add(statusLabel);
            topPanel = top;

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logArea.setBackground(Theme.CREAM);
            logArea.setForeground(Theme.INK);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            bottom.setBackground(Theme.DUSTY_PINK);
            addressLabel.setForeground(Theme.WINE);
            bottom.add(addressLabel);

            add(top, BorderLayout.NORTH);
            add(new JScrollPane(logArea), BorderLayout.CENTER);
            bottomPanel = bottom;
            add(bottom, BorderLayout.SOUTH);
            add(buildHostPanel(), BorderLayout.EAST);

            toggleBtn.addActionListener(e -> toggle());
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    server.stop();
                    dispose();
                    System.exit(0);
                }
            });
            setSize(1000, 720);
            setLocationRelativeTo(null);
            applyColors();
        }

        private void applyColors() {
            topPanel.setBackground(Theme.DEEP_ROSE);
            logArea.setBackground(Theme.CREAM);
            logArea.setForeground(Theme.INK);
            bottomPanel.setBackground(Theme.DUSTY_PINK);
            addressLabel.setForeground(Theme.WINE);
            hostUserList.setBackground(Theme.CREAM);
            hostUserList.setForeground(Theme.INK);
            hostUserList.setSelectionBackground(Theme.DEEP_ROSE);
            hostUserList.setSelectionForeground(Color.WHITE);
            removedList.setBackground(Theme.CREAM);
            removedList.setForeground(Theme.INK);
            removedList.setSelectionBackground(Theme.DEEP_ROSE);
            removedList.setSelectionForeground(Color.WHITE);
        }

        /** Right-hand side of the server window: Kick, Announcement and Study timer. */
        private JPanel buildHostPanel() {
            hostUserList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            hostUserList.setBackground(Theme.CREAM);
            hostUserList.setForeground(Theme.INK);
            hostUserList.setSelectionBackground(Theme.DEEP_ROSE);
            hostUserList.setSelectionForeground(Color.WHITE);

            JPanel usersBox = new JPanel(new BorderLayout(0, 6));
            usersBox.setBorder(BorderFactory.createTitledBorder("Online users"));
            usersBox.add(new JScrollPane(hostUserList), BorderLayout.CENTER);
            usersBox.add(kickBtn, BorderLayout.SOUTH);

            removedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JPanel removedButtons = new JPanel(new GridLayout(1, 2, 6, 0));
            removedButtons.add(allowBtn);
            removedButtons.add(denyBtn);
            removedBox = new JPanel(new BorderLayout(0, 6));
            removedBox.setBorder(removedBorder);
            removedBox.add(new JScrollPane(removedList), BorderLayout.CENTER);
            removedBox.add(removedButtons, BorderLayout.SOUTH);
            JPanel middle = new JPanel(new GridLayout(2, 1, 0, 6));
            middle.add(usersBox);
            middle.add(removedBox);

            JPanel announceBox = new JPanel(new BorderLayout(0, 6));
            announceBox.setBorder(BorderFactory.createTitledBorder("Announcement to everyone"));
            announceBox.add(announceField, BorderLayout.CENTER);
            announceBox.add(announceBtn, BorderLayout.SOUTH);

            JPanel minutesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            minutesRow.add(new JLabel("Time:"));
            minutesRow.add(minutesSpinner);
            minutesRow.add(unitBox);
            unitBox.addActionListener(e -> {
                // the biggest allowed number depends on the unit: 43200 minutes, 720 hours or 30 days
                SpinnerNumberModel model = (SpinnerNumberModel) minutesSpinner.getModel();
                int max = selectedUnit().maxAmount();
                model.setMaximum(max);
                if (((Number) model.getValue()).intValue() > max) {
                    model.setValue(max);
                }
            });
            JPanel timerButtons = new JPanel(new GridLayout(1, 2, 6, 0));
            timerButtons.add(startTimerBtn);
            timerButtons.add(stopTimerBtn);
            timerLabel.setFont(timerLabel.getFont().deriveFont(Font.BOLD));
            JPanel timerBox = new JPanel(new BorderLayout(0, 6));
            timerBox.setBorder(BorderFactory.createTitledBorder("Study timer (shown to everyone)"));
            timerBox.add(minutesRow, BorderLayout.NORTH);
            timerBox.add(timerButtons, BorderLayout.CENTER);
            timerBox.add(timerLabel, BorderLayout.SOUTH);

            JPanel lower = new JPanel(new GridLayout(2, 1, 0, 6));
            lower.add(announceBox);
            lower.add(timerBox);

            JPanel host = new JPanel(new BorderLayout(0, 6));
            host.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Host controls"), BorderFactory.createEmptyBorder(2, 6, 6, 6)));
            JPanel toolsTab = new JPanel(new BorderLayout());
            toolsTab.add(lower, BorderLayout.NORTH);
            JTabbedPane hostTabs = new JTabbedPane();
            hostTabs.addTab("People", middle);
            hostTabs.addTab("Announce & Timer", toolsTab);
            hostTabs.addTab("Quiz", buildQuizPanel());
            host.add(hostTabs, BorderLayout.CENTER);
            host.setPreferredSize(new Dimension(370, 0));

            kickBtn.addActionListener(e -> {
                String name = hostUserList.getSelectedValue();
                if (name == null) {
                    return;
                }
                int answer = JOptionPane.showConfirmDialog(this,
                        "Remove " + name + " from the chat?\n\n" + name + " will need YOUR permission to join again.",
                        "Kick user", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    server.kickUser(name);
                    refreshHostPanel();
                }
            });
            ActionListener sendAnnouncement = e -> {
                String err = server.announce(announceField.getText());
                if (err != null) {
                    JOptionPane.showMessageDialog(this, err, "Announcement", JOptionPane.WARNING_MESSAGE);
                } else {
                    announceField.setText("");
                }
            };
            allowBtn.addActionListener(e -> {
                StudyServer.RemovedUser r = removedList.getSelectedValue();
                if (r != null) {
                    server.allowUser(r.nick);
                    refreshHostPanel();
                }
            });
            denyBtn.addActionListener(e -> {
                StudyServer.RemovedUser r = removedList.getSelectedValue();
                if (r != null) {
                    server.denyUser(r.nick);
                    refreshHostPanel();
                }
            });
            removedList.addListSelectionListener(e -> refreshHostPanel());
            announceBtn.addActionListener(sendAnnouncement);
            announceField.addActionListener(sendAnnouncement);
            startTimerBtn.addActionListener(e -> {
                try {
                    minutesSpinner.commitEdit();   // also take a number that was typed but not confirmed with Enter
                } catch (java.text.ParseException ignored) {
                }
                String err = server.startTimer(((Number) minutesSpinner.getValue()).intValue(), selectedUnit());
                if (err != null) {
                    JOptionPane.showMessageDialog(this, err, "Study timer", JOptionPane.WARNING_MESSAGE);
                }
                refreshHostPanel();
            });
            stopTimerBtn.addActionListener(e -> {
                server.stopTimer();
                refreshHostPanel();
            });
            hostUserList.addListSelectionListener(e -> refreshHostPanel());
            new javax.swing.Timer(1000, e -> refreshHostPanel()).start();   // keeps the user list and timer fresh
            refreshHostPanel();
            return host;
        }

        /** The Quiz tab: write a multiple-choice question, send it, watch the leaderboard. */
        private JPanel buildQuizPanel() {
            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.setBorder(BorderFactory.createTitledBorder("New question"));
            JLabel questionLabel = new JLabel("Question:");
            questionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(questionLabel);
            quizQuestionField.setAlignmentX(Component.LEFT_ALIGNMENT);
            quizQuestionField.setMaximumSize(new Dimension(Integer.MAX_VALUE, quizQuestionField.getPreferredSize().height));
            form.add(quizQuestionField);
            form.add(Box.createVerticalStrut(6));
            JLabel choicesLabel = new JLabel("Answer choices (tick the correct one):");
            choicesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(choicesLabel);
            ButtonGroup group = new ButtonGroup();
            for (int i = 0; i < 4; i++) {
                JPanel row = new JPanel(new BorderLayout(6, 0));
                row.add(new JLabel((char) ('A' + i) + ":"), BorderLayout.WEST);
                row.add(quizOptionFields[i], BorderLayout.CENTER);
                quizCorrect[i] = new JRadioButton("correct");
                group.add(quizCorrect[i]);
                row.add(quizCorrect[i], BorderLayout.EAST);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                form.add(row);
                form.add(Box.createVerticalStrut(3));
            }
            quizCorrect[0].setSelected(true);
            JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            timeRow.add(new JLabel("Time to answer:"));
            timeRow.add(quizSecondsSpinner);
            timeRow.add(new JLabel("seconds"));
            timeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(timeRow);
            form.add(Box.createVerticalStrut(6));
            JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
            buttons.add(quizStartBtn);
            buttons.add(quizEndBtn);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
            form.add(buttons);
            form.add(Box.createVerticalStrut(6));
            quizStatusLabel.setFont(quizStatusLabel.getFont().deriveFont(Font.BOLD));
            quizStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(quizStatusLabel);

            boardList.setBackground(Theme.CREAM);
            boardList.setForeground(Theme.INK);
            JPanel boardBox = new JPanel(new BorderLayout(0, 6));
            boardBox.setBorder(BorderFactory.createTitledBorder("Leaderboard (most correct first)"));
            boardBox.add(new JScrollPane(boardList), BorderLayout.CENTER);
            boardBox.add(quizResetBtn, BorderLayout.SOUTH);

            quizStartBtn.addActionListener(e -> {
                try {
                    quizSecondsSpinner.commitEdit();
                } catch (java.text.ParseException ignored) {
                }
                List<String> options = new ArrayList<>();
                int correct = 0;
                for (int i = 0; i < 4; i++) {
                    options.add(quizOptionFields[i].getText());
                    if (quizCorrect[i].isSelected()) {
                        correct = i;
                    }
                }
                String err = server.startQuiz(quizQuestionField.getText(), options, correct,
                        ((Number) quizSecondsSpinner.getValue()).intValue());
                if (err != null) {
                    JOptionPane.showMessageDialog(this, err, "Quiz", JOptionPane.WARNING_MESSAGE);
                } else {
                    quizQuestionField.setText("");   // ready for the next question
                    for (JTextField field : quizOptionFields) {
                        field.setText("");
                    }
                    quizCorrect[0].setSelected(true);
                }
                refreshHostPanel();
            });
            quizEndBtn.addActionListener(e -> {
                server.endQuiz();
                refreshHostPanel();
            });
            quizResetBtn.addActionListener(e -> {
                int answer = JOptionPane.showConfirmDialog(this, "Reset the leaderboard? Everyone's score goes back to 0.",
                        "Quiz", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    server.resetQuizScores();
                    refreshHostPanel();
                }
            });

            JPanel panel = new JPanel(new BorderLayout(0, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            panel.add(form, BorderLayout.NORTH);
            panel.add(boardBox, BorderLayout.CENTER);
            return panel;
        }

        private StudyServer.TimerUnit selectedUnit() {
            switch (unitBox.getSelectedIndex()) {
                case 1:
                    return StudyServer.TimerUnit.HOURS;
                case 2:
                    return StudyServer.TimerUnit.DAYS;
                default:
                    return StudyServer.TimerUnit.MINUTES;
            }
        }

        private void refreshHostPanel() {
            boolean running = server.isRunning();
            List<String> names = running ? server.getUserNames() : new ArrayList<String>();
            List<String> shown = Collections.list(hostUserModel.elements());
            if (!names.equals(shown)) {
                String keep = hostUserList.getSelectedValue();
                hostUserModel.clear();
                for (String n : names) {
                    hostUserModel.addElement(n);
                }
                if (keep != null && names.contains(keep)) {
                    hostUserList.setSelectedValue(keep, true);
                }
            }
            List<StudyServer.RemovedUser> removed = running ? server.getRemovedUsers() : new ArrayList<StudyServer.RemovedUser>();
            if (!removed.equals(Collections.list(removedModel.elements()))) {
                StudyServer.RemovedUser keep = removedList.getSelectedValue();
                removedModel.clear();
                for (StudyServer.RemovedUser r : removed) {
                    removedModel.addElement(r);
                }
                if (keep != null) {
                    for (int i = 0; i < removedModel.size(); i++) {
                        if (removedModel.get(i).nick.equals(keep.nick)) {
                            removedList.setSelectedIndex(i);
                        }
                    }
                }
            }
            int waitingNow = 0;
            for (StudyServer.RemovedUser r : removed) {
                if (r.waiting) {
                    waitingNow++;
                }
            }
            if (waitingNow > lastWaiting) {
                Toolkit.getDefaultToolkit().beep();   // somebody is asking to come back
            }
            lastWaiting = waitingNow;
            removedBorder.setTitle(waitingNow > 0
                    ? "Removed users - " + waitingNow + " WAITING for your permission"
                    : "Removed users (they need your permission to come back)");
            removedBox.repaint();
            StudyServer.RemovedUser sel = removedList.getSelectedValue();
            allowBtn.setEnabled(running && sel != null);
            denyBtn.setEnabled(running && sel != null && sel.waiting);

            boolean quizOpen = running && server.isQuizOpen();
            quizStatusLabel.setText(running ? server.quizStatus() : "The server is stopped.");
            quizStartBtn.setEnabled(running && !quizOpen);
            quizEndBtn.setEnabled(quizOpen);
            quizResetBtn.setEnabled(running);
            List<String> boardLines = running ? server.getLeaderboardLines() : new ArrayList<String>();
            if (!boardLines.equals(Collections.list(boardModel.elements()))) {
                boardModel.clear();
                for (String line : boardLines) {
                    boardModel.addElement(line);
                }
            }

            long left = server.timerSecondsLeft();
            kickBtn.setEnabled(running && hostUserList.getSelectedValue() != null);
            announceField.setEnabled(running);
            announceBtn.setEnabled(running);
            minutesSpinner.setEnabled(running);
            unitBox.setEnabled(running);
            startTimerBtn.setEnabled(running);
            stopTimerBtn.setEnabled(running && left > 0);
            timerLabel.setText(left > 0
                    ? "Timer running: " + Protocol.formatDuration(left) + " left"
                    : "Timer: not running");
        }

        private void toggle() {
            if (server.isRunning()) {
                server.stop();
                toggleBtn.setText("Start server");
                portField.setEnabled(true);
                statusLabel.setText("Server stopped.");
                addressLabel.setText(" ");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Port must be a number from 1 to 65535.", "Invalid port", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                server.start(port);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not start the server on port " + port + ":\n" + ex.getMessage()
                        + "\n(Is another server already using this port?)", "Cannot start", JOptionPane.ERROR_MESSAGE);
                return;
            }
            toggleBtn.setText("Stop server");
            portField.setEnabled(false);
            statusLabel.setText("Running on port " + server.getPort());
            List<String> addrs = localAddresses();
            addressLabel.setText(addrs.isEmpty()
                    ? "No LAN address found - check Wi-Fi/LAN connection."
                    : "Clients on the same network connect to: " + String.join("  or  ", addrs) + "   (port " + server.getPort() + ")");
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        boolean noGui = GraphicsEnvironment.isHeadless();
        int port = Protocol.DEFAULT_PORT;
        for (String a : args) {
            if (a.equals("--nogui")) {
                noGui = true;
            } else {
                try {
                    port = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    System.err.println("Ignoring unknown argument: " + a);
                }
            }
        }
        if (noGui) {
            StudyServer s = new StudyServer(System.out::println);
            s.start(port);
            System.out.println("LAN addresses: " + localAddresses() + "  port " + s.getPort());
            System.out.println("Press Enter to stop the server.");
            if (System.in.read() == -1) {
                Thread.sleep(Long.MAX_VALUE);   // no console attached: keep running
            }
            s.stop();
        } else {
            final int p = port;
            Theme.apply();
            SwingUtilities.invokeLater(() -> new ServerWindow(p).setVisible(true));
        }
    }
}
