import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared constants and helpers for the Study Circle protocol.
 *
 * Every message is ONE line of UTF-8 text. Fields inside a line are separated
 * by a TAB character, so user text must be cleaned (see clean()) before it is sent.
 */
public final class Protocol {

    private Protocol() { }

    public static final int DEFAULT_PORT = 5000;

    public static final int MAX_NICK = 20;
    public static final int MAX_CHAT = 500;
    public static final int MAX_QUESTION = 200;
    public static final int MAX_REPLY = 300;
    public static final int MAX_REPLIES_PER_QUESTION = 50;
    public static final int MAX_QUIZ_QUESTION = 200;
    public static final int MAX_QUIZ_OPTION = 80;
    public static final int MIN_QUIZ_OPTIONS = 2;
    public static final int MAX_QUIZ_OPTIONS = 4;
    public static final int MIN_QUIZ_SECONDS = 5;
    public static final int MAX_QUIZ_SECONDS = 300;
    public static final int MAX_TIMER_MINUTES = 30 * 24 * 60;   // the longest study timer: 30 days
    public static final int NAME_COLORS = 10;   // how many different name colours the server hands out
    public static final int MAX_POLL_QUESTION = 120;
    public static final int MAX_POLL_OPTION = 60;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 5;

    /** A sent chat message can be edited only during this time (5 minutes). Deleting has no time limit. */
    public static final long EDIT_WINDOW_MS = 5 * 60 * 1000L;

    public static final String SEP = "\t";

    /** Letters, digits, underscore and hyphen only (no spaces, so names are easy to type in commands). */
    public static final Pattern NICK_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_NICK + "}");

    // ---- client -> server ----
    public static final String C_JOIN = "JOIN";           // JOIN  nick
    public static final String C_CHAT = "CHAT";           // CHAT  text   (text may be a /command)
    public static final String C_REPLY_MSG = "REPLYMSG"; // REPLYMSG messageId text   (reply to a chat message)
    public static final String C_QUIZ_ANSWER = "QZANSWER"; // QZANSWER quizId optionIndex   (one answer per question)
    public static final String C_PM = "PM";               // PM toNick text   (private message)
    public static final String C_EDIT = "EDIT";           // EDIT  messageId newText   (own message, within 5 minutes)
    public static final String C_DELETE = "DELETE";       // DELETE messageId          (own message)
    public static final String C_ASK = "ASK";             // ASK   questionText
    public static final String C_UPVOTE = "UPVOTE";       // UPVOTE questionId   (toggles)
    public static final String C_RESOLVE = "RESOLVE";     // RESOLVE questionId  (toggles)
    public static final String C_UNRESOLVE = "UNRESOLVE"; // UNRESOLVE questionId (asker only; reopens a resolved question)
    public static final String C_Q_EDIT = "QEDIT";        // QEDIT questionId newText   (asker only)
    public static final String C_Q_DELETE = "QDELETE";    // QDELETE questionId         (asker only)
    public static final String C_Q_REPLY = "QREPLY";      // QREPLY questionId text     (anyone)
    public static final String C_R_EDIT = "REDIT";        // REDIT replyId newText      (author only)
    public static final String C_R_DELETE = "RDELETE";    // RDELETE replyId            (author only)
    public static final String C_POLL_START = "POLLSTART";// POLLSTART question opt1 opt2 ...
    public static final String C_VOTE = "VOTE";           // VOTE pollId optionIndex
    public static final String C_POLL_END = "POLLEND";    // POLLEND pollId

    // ---- server -> client ----
    public static final String S_JOINED = "JOINED";       // JOINED nick
    public static final String S_ERROR = "ERROR";         // ERROR message
    public static final String S_MSG = "MSG";             // MSG time sender text messageId [replySender replyPreview]
    public static final String S_QUIZ = "QUIZ";           // QUIZ id secondsLeft question n option*n   (a question starts)
    public static final String S_QUIZ_ACK = "QZACK";      // QZACK id optionIndex                      (your answer was accepted)
    public static final String S_QUIZ_RESULT = "QZRESULT";// QZRESULT id correctIndex n count*n        (the question is over)
    public static final String S_QUIZ_BOARD = "QZBOARD";  // QZBOARD n (nick correct answered)*n       (leaderboard)
    public static final String S_PM = "PMSG";             // PMSG time from to text   (only sender and receiver get it)
    public static final String S_ANNOUNCE = "ANNOUNCE";   // ANNOUNCE time text      (from the host, to everyone)
    public static final String S_TIMER = "TIMER";         // TIMER secondsLeft state (state: running | finished | stopped)
    public static final String S_WAIT = "WAIT";           // WAIT message   (you were removed: the host must allow you back first)
    public static final String S_ALLOWED = "ALLOWED";     // ALLOWED        (the host allowed you: send JOIN again)
    public static final String S_DENIED = "DENIED";       // DENIED         (the host refused; the connection closes)
    public static final String S_KICKED = "KICKED";       // KICKED                  (the host removed you; BYE follows)
    public static final String S_EDITED = "EDITED";       // EDITED messageId newText
    public static final String S_DELETED = "DELETED";     // DELETED messageId
    public static final String S_SYS = "SYS";             // SYS time text
    public static final String S_COLORS = "COLORS";       // COLORS nick colourIndex nick colourIndex ...  (each user keeps one colour)
    public static final String S_USERS = "USERS";         // USERS nick1 nick2 ...
    public static final String S_NICK = "NICK";           // NICK newNick  (your nickname changed)
    public static final String S_QUEUE = "QUEUE";         // QUEUE n (id votes resolved mine asker text nReplies (replyId author time text)*nReplies)*n
    public static final String S_POLL = "POLL";           // POLL NONE | POLL id creator closed question myVote n (opt count)*n
    public static final String S_BYE = "BYE";             // BYE

    /**
     * Time left, written so it stays readable for short and long timers:
     * 04:59 (under an hour), 2:05:09 (under a day), 1d 02:05:09 (a day or more).
     */
    public static String formatDuration(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long minutes = (s % 3600) / 60;
        long seconds = s % 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, seconds);
        }
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /** Replace tabs/newlines (which would break the protocol) and trim. Never returns null. */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    /** Join fields with the separator. */
    public static String line(String... parts) {
        return String.join(SEP, parts);
    }

    /** @return an error message, or null when the nickname is acceptable. */
    public static String checkNick(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            return "Nickname cannot be blank.";
        }
        if (!NICK_PATTERN.matcher(nick).matches()) {
            return "Nickname must be 1-" + MAX_NICK + " characters: letters, digits, _ or - only (no spaces).";
        }
        return null;
    }
}
